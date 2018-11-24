package org.thoughtcrime.securesms.service;


import android.Manifest;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.ResultReceiver;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.WorkerThread;
import android.telephony.TelephonyManager;

import org.thoughtcrime.securesms.logging.Log;

import android.util.Pair;

import com.google.protobuf.InvalidProtocolBufferException;

import org.greenrobot.eventbus.EventBus;
import org.thoughtcrime.securesms.ApplicationContext;
import org.thoughtcrime.securesms.WebRtcCallActivity;
import org.thoughtcrime.securesms.contacts.ContactAccessor;
import org.thoughtcrime.securesms.crypto.UnidentifiedAccessUtil;
import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.RecipientDatabase.VibrateState;
import org.thoughtcrime.securesms.dependencies.InjectableType;
import org.thoughtcrime.securesms.events.WebRtcViewModel;
import org.thoughtcrime.securesms.notifications.MessageNotifier;
import org.thoughtcrime.securesms.permissions.Permissions;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.FutureTaskListener;
import org.thoughtcrime.securesms.util.ListenableFutureTask;
import org.thoughtcrime.securesms.util.ServiceUtil;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.util.Util;
import org.thoughtcrime.securesms.webrtc.CallNotificationBuilder;
import org.thoughtcrime.securesms.webrtc.CameraState;
import org.thoughtcrime.securesms.webrtc.IncomingPstnCallReceiver;
import org.thoughtcrime.securesms.webrtc.PeerConnectionFactoryOptions;
import org.thoughtcrime.securesms.webrtc.PeerConnectionWrapper;
import org.thoughtcrime.securesms.webrtc.PeerConnectionWrapper.PeerConnectionException;
import org.thoughtcrime.securesms.webrtc.UncaughtExceptionHandlerManager;
import org.thoughtcrime.securesms.webrtc.WebRtcDataProtos;
import org.thoughtcrime.securesms.webrtc.WebRtcDataProtos.Connected;
import org.thoughtcrime.securesms.webrtc.WebRtcDataProtos.Data;
import org.thoughtcrime.securesms.webrtc.WebRtcDataProtos.Hangup;
import org.thoughtcrime.securesms.webrtc.audio.BluetoothStateManager;
import org.thoughtcrime.securesms.webrtc.audio.OutgoingRinger;
import org.thoughtcrime.securesms.webrtc.audio.SignalAudioManager;
import org.thoughtcrime.securesms.webrtc.locks.LockManager;
import org.webrtc.AudioTrack;
import org.webrtc.DataChannel;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoTrack;
import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.signalservice.api.SignalServiceAccountManager;
import org.whispersystems.signalservice.api.SignalServiceMessageSender;
import org.whispersystems.signalservice.api.crypto.UntrustedIdentityException;
import org.whispersystems.signalservice.api.messages.calls.AnswerMessage;
import org.whispersystems.signalservice.api.messages.calls.BusyMessage;
import org.whispersystems.signalservice.api.messages.calls.HangupMessage;
import org.whispersystems.signalservice.api.messages.calls.IceUpdateMessage;
import org.whispersystems.signalservice.api.messages.calls.OfferMessage;
import org.whispersystems.signalservice.api.messages.calls.SignalServiceCallMessage;
import org.whispersystems.signalservice.api.messages.calls.TurnServerInfo;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.api.push.exceptions.UnregisteredUserException;
import org.whispersystems.signalservice.internal.util.concurrent.SettableFuture;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import static org.thoughtcrime.securesms.webrtc.CallNotificationBuilder.TYPE_ESTABLISHED;
import static org.thoughtcrime.securesms.webrtc.CallNotificationBuilder.TYPE_INCOMING_CONNECTING;
import static org.thoughtcrime.securesms.webrtc.CallNotificationBuilder.TYPE_INCOMING_RINGING;
import static org.thoughtcrime.securesms.webrtc.CallNotificationBuilder.TYPE_OUTGOING_RINGING;

/**
 * WebRTC回话服务
 */
public class WebRtcCallService extends Service implements InjectableType,
        PeerConnection.Observer,
        DataChannel.Observer,
        BluetoothStateManager.BluetoothStateListener,
        PeerConnectionWrapper.CameraEventListener {

    private static final String TAG = WebRtcCallService.class.getSimpleName();

    private enum CallState {
        STATE_IDLE, STATE_DIALING, STATE_ANSWERING, STATE_REMOTE_RINGING, STATE_LOCAL_RINGING, STATE_CONNECTED
    }

    private static final String DATA_CHANNEL_NAME = "signaling";

    public static final String EXTRA_REMOTE_ADDRESS = "remote_address";
    public static final String EXTRA_MUTE = "mute_value";
    public static final String EXTRA_AVAILABLE = "enabled_value";
    public static final String EXTRA_REMOTE_DESCRIPTION = "remote_description";
    public static final String EXTRA_TIMESTAMP = "timestamp";
    public static final String EXTRA_CALL_ID = "call_id";
    public static final String EXTRA_ICE_SDP = "ice_sdp";
    public static final String EXTRA_ICE_SDP_MID = "ice_sdp_mid";
    public static final String EXTRA_ICE_SDP_LINE_INDEX = "ice_sdp_line_index";
    public static final String EXTRA_RESULT_RECEIVER = "result_receiver";

    public static final String ACTION_INCOMING_CALL = "CALL_INCOMING";
    public static final String ACTION_OUTGOING_CALL = "CALL_OUTGOING";
    public static final String ACTION_ANSWER_CALL = "ANSWER_CALL";
    public static final String ACTION_DENY_CALL = "DENY_CALL";
    public static final String ACTION_LOCAL_HANGUP = "LOCAL_HANGUP";
    public static final String ACTION_SET_MUTE_AUDIO = "SET_MUTE_AUDIO";
    public static final String ACTION_SET_MUTE_VIDEO = "SET_MUTE_VIDEO";
    public static final String ACTION_FLIP_CAMERA = "FLIP_CAMERA";
    public static final String ACTION_BLUETOOTH_CHANGE = "BLUETOOTH_CHANGE";
    public static final String ACTION_WIRED_HEADSET_CHANGE = "WIRED_HEADSET_CHANGE";
    public static final String ACTION_SCREEN_OFF = "SCREEN_OFF";
    public static final String ACTION_CHECK_TIMEOUT = "CHECK_TIMEOUT";
    public static final String ACTION_IS_IN_CALL_QUERY = "IS_IN_CALL";

    public static final String ACTION_RESPONSE_MESSAGE = "RESPONSE_MESSAGE";
    public static final String ACTION_ICE_MESSAGE = "ICE_MESSAGE";
    public static final String ACTION_ICE_CANDIDATE = "ICE_CANDIDATE";
    public static final String ACTION_CALL_CONNECTED = "CALL_CONNECTED";
    public static final String ACTION_REMOTE_HANGUP = "REMOTE_HANGUP";
    public static final String ACTION_REMOTE_BUSY = "REMOTE_BUSY";
    public static final String ACTION_REMOTE_VIDEO_MUTE = "REMOTE_VIDEO_MUTE";
    public static final String ACTION_ICE_CONNECTED = "ICE_CONNECTED";

    private CallState callState = CallState.STATE_IDLE;
    private CameraState localCameraState = CameraState.UNKNOWN;
    private boolean microphoneEnabled = true;
    private boolean remoteVideoEnabled = false;
    private boolean bluetoothAvailable = false;

    @Inject
    public SignalServiceMessageSender messageSender;
    @Inject
    public SignalServiceAccountManager accountManager;

    private PeerConnectionFactory peerConnectionFactory;       // 整个WebRTC中最核心的类，有了这个类才能获得音视频相关的其他操作
    private SignalAudioManager audioManager;                // 音频管理器
    private BluetoothStateManager bluetoothStateManager;       // 蓝牙状态管理器
    private WiredHeadsetStateReceiver wiredHeadsetStateReceiver;   //
    private PowerButtonReceiver powerButtonReceiver;
    private LockManager lockManager;                 // 锁屏管理器

    private IncomingPstnCallReceiver callReceiver;
    private UncaughtExceptionHandlerManager uncaughtExceptionHandlerManager;

    @Nullable
    private Long callId;                      // 通话ID
    @Nullable
    private Recipient recipient;                   // 接收人
    @Nullable
    private PeerConnectionWrapper peerConnection;
    @Nullable
    private DataChannel dataChannel;                 // 数据通道
    @Nullable
    private List<IceUpdateMessage> pendingOutgoingIceUpdates;
    @Nullable
    private List<IceCandidate> pendingIncomingIceUpdates;

    @Nullable
    public static SurfaceViewRenderer localRenderer;           // 本地视频渲染器
    @Nullable
    public static SurfaceViewRenderer remoteRenderer;          // 远程视频渲染器
    // EGL™ 是介于诸如OpenGL 或OpenVG的Khronos渲染API与底层本地平台窗口系统的接口。
    // 它被用于处理图形管理、表面/缓冲捆绑、渲染同步及支援使用其他Khronos API进行的高效、加速、混合模式2D和3D渲染。
    @Nullable
    private static EglBase eglBase;

    private ExecutorService serviceExecutor = Executors.newSingleThreadExecutor();                 // 服务线程池
    private ExecutorService networkExecutor = Executors.newSingleThreadExecutor();                 // 网络线程池
    private ScheduledExecutorService timeoutExecutor = Executors.newScheduledThreadPool(1);   // 超时线程池

    @Override
    public void onCreate() {
        super.onCreate();

        initializeResources();

        registerIncomingPstnCallReceiver();
        registerUncaughtExceptionHandler();
        registerWiredHeadsetStateReceiver();
    }

    @Override
    public int onStartCommand(final Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand...");
        if (intent == null || intent.getAction() == null) return START_NOT_STICKY;

        serviceExecutor.execute(() -> {
            if (intent.getAction().equals(ACTION_INCOMING_CALL) && isBusy()) handleBusyCall(intent);
            else if (intent.getAction().equals(ACTION_REMOTE_BUSY)) handleBusyMessage(intent);
            else if (intent.getAction().equals(ACTION_INCOMING_CALL))
                handleIncomingCall(intent);   // 来电
            else if (intent.getAction().equals(ACTION_OUTGOING_CALL) && isIdle())
                handleOutgoingCall(intent);   // 去电
            else if (intent.getAction().equals(ACTION_ANSWER_CALL))
                handleAnswerCall(intent);     // 接听
            else if (intent.getAction().equals(ACTION_DENY_CALL))
                handleDenyCall(intent);       // 挂断
            else if (intent.getAction().equals(ACTION_LOCAL_HANGUP)) handleLocalHangup(intent);
            else if (intent.getAction().equals(ACTION_REMOTE_HANGUP)) handleRemoteHangup(intent);
            else if (intent.getAction().equals(ACTION_SET_MUTE_AUDIO)) handleSetMuteAudio(intent);
            else if (intent.getAction().equals(ACTION_SET_MUTE_VIDEO)) handleSetMuteVideo(intent);
            else if (intent.getAction().equals(ACTION_FLIP_CAMERA)) handleSetCameraFlip(intent);
            else if (intent.getAction().equals(ACTION_BLUETOOTH_CHANGE))
                handleBluetoothChange(intent);
            else if (intent.getAction().equals(ACTION_WIRED_HEADSET_CHANGE))
                handleWiredHeadsetChange(intent);
            else if (intent.getAction().equals((ACTION_SCREEN_OFF))) handleScreenOffChange(intent);
            else if (intent.getAction().equals(ACTION_REMOTE_VIDEO_MUTE))
                handleRemoteVideoMute(intent);
            else if (intent.getAction().equals(ACTION_RESPONSE_MESSAGE))
                handleResponseMessage(intent);
            else if (intent.getAction().equals(ACTION_ICE_MESSAGE))
                handleRemoteIceCandidate(intent);
            else if (intent.getAction().equals(ACTION_ICE_CANDIDATE))
                handleLocalIceCandidate(intent);
            else if (intent.getAction().equals(ACTION_ICE_CONNECTED)) handleIceConnected(intent);
            else if (intent.getAction().equals(ACTION_CALL_CONNECTED)) handleCallConnected(intent);
            else if (intent.getAction().equals(ACTION_CHECK_TIMEOUT)) handleCheckTimeout(intent);
            else if (intent.getAction().equals(ACTION_IS_IN_CALL_QUERY))
                handleIsInCallQuery(intent);
        });

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (callReceiver != null) {
            unregisterReceiver(callReceiver);
        }

        if (uncaughtExceptionHandlerManager != null) {
            uncaughtExceptionHandlerManager.unregister();
        }

        if (bluetoothStateManager != null) {
            bluetoothStateManager.onDestroy();
        }

        if (wiredHeadsetStateReceiver != null) {
            unregisterReceiver(wiredHeadsetStateReceiver);
            wiredHeadsetStateReceiver = null;
        }

        if (powerButtonReceiver != null) {
            unregisterReceiver(powerButtonReceiver);
            powerButtonReceiver = null;
        }
    }

    @Override
    public void onBluetoothStateChanged(boolean isAvailable) {
        Log.i(TAG, "onBluetoothStateChanged: " + isAvailable);

        Intent intent = new Intent(this, WebRtcCallService.class);
        intent.setAction(ACTION_BLUETOOTH_CHANGE);
        intent.putExtra(EXTRA_AVAILABLE, isAvailable);

        startService(intent);
    }

    @Override
    public void onCameraSwitchCompleted(@NonNull CameraState newCameraState) {
        this.localCameraState = newCameraState;
        if (recipient != null) {
            sendMessage(viewModelStateFor(callState), recipient, localCameraState, remoteVideoEnabled, bluetoothAvailable, microphoneEnabled);
        }
    }


    // Initializers

    private void initializeResources() {
        ApplicationContext.getInstance(this).injectDependencies(this);

        this.callState = CallState.STATE_IDLE;
        this.lockManager = new LockManager(this);
        this.peerConnectionFactory = PeerConnectionFactory.builder().setOptions(new PeerConnectionFactoryOptions()).createPeerConnectionFactory();
        this.audioManager = new SignalAudioManager(this);
        this.bluetoothStateManager = new BluetoothStateManager(this, this);
        this.messageSender.setSoTimeoutMillis(TimeUnit.SECONDS.toMillis(10));
        this.accountManager.setSoTimeoutMillis(TimeUnit.SECONDS.toMillis(10));
    }

    private void registerIncomingPstnCallReceiver() {
        callReceiver = new IncomingPstnCallReceiver();
        registerReceiver(callReceiver, new IntentFilter("android.intent.action.PHONE_STATE"));
    }

    private void registerUncaughtExceptionHandler() {
        uncaughtExceptionHandlerManager = new UncaughtExceptionHandlerManager();
        uncaughtExceptionHandlerManager.registerHandler(new ProximityLockRelease(lockManager));
    }

    private void registerWiredHeadsetStateReceiver() {
        wiredHeadsetStateReceiver = new WiredHeadsetStateReceiver();

        String action;

        if (Build.VERSION.SDK_INT >= 21) {
            action = AudioManager.ACTION_HEADSET_PLUG;
        } else {
            action = Intent.ACTION_HEADSET_PLUG;
        }

        registerReceiver(wiredHeadsetStateReceiver, new IntentFilter(action));
    }

    private void registerPowerButtonReceiver() {
        if (powerButtonReceiver == null) {
            powerButtonReceiver = new PowerButtonReceiver();

            registerReceiver(powerButtonReceiver, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        }
    }

    private void unregisterPowerButtonReceiver() {
        if (powerButtonReceiver != null) {
            unregisterReceiver(powerButtonReceiver);

            powerButtonReceiver = null;
        }
    }

    // Handlers

    /**
     * 处理来电
     *
     * @param intent
     */
    private void handleIncomingCall(final Intent intent) {
        Log.i(TAG, "handleIncomingCall()");
        if (callState != CallState.STATE_IDLE)
            throw new IllegalStateException("Incoming on non-idle");   // 非空闲状态

        final String offer = intent.getStringExtra(EXTRA_REMOTE_DESCRIPTION);

        this.callState = CallState.STATE_ANSWERING;                             // 设置为通话中状态
        this.callId = intent.getLongExtra(EXTRA_CALL_ID, -1);   // 获取通话ID
        this.pendingIncomingIceUpdates = new LinkedList<>();
        this.recipient = getRemoteRecipient(intent);                            // 获取接受者

        if (isIncomingMessageExpired(intent)) {            // 2分钟内未接来电
            insertMissedCall(this.recipient, true);   // 插入未接来电数据
            terminate();                                     // 结束通话
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {     // 8.0以上显示通话进度通知
            setCallInProgressNotification(TYPE_INCOMING_CONNECTING, this.recipient);
        }

        timeoutExecutor.schedule(new TimeoutRunnable(this.callId), 2, TimeUnit.MINUTES);    // 通话心跳

        initializeVideo();    // 初始化视频资源

        // 检索中继器服务器地址成功后
        retrieveTurnServers().addListener(new SuccessOnlyListener<List<PeerConnection.IceServer>>(this.callState, this.callId) {
            @Override
            public void onSuccessContinue(List<PeerConnection.IceServer> result) {
                try {
                    boolean isSystemContact = false;  // 是否是系统联系人，默认为false

                    // 是否有读写联系人权限
                    if (Permissions.hasAny(WebRtcCallService.this, Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)) {
                        isSystemContact = ContactAccessor.getInstance().isSystemContact(WebRtcCallService.this, recipient.getAddress().serialize());
                    }

                    // 是否总是转接通话
                    boolean isAlwaysTurn = TextSecurePreferences.isTurnOnly(WebRtcCallService.this);

                    // 建立数据的“点对点”（peer to peer）通信
                    WebRtcCallService.this.peerConnection = new PeerConnectionWrapper(WebRtcCallService.this, peerConnectionFactory, WebRtcCallService.this, localRenderer, result, WebRtcCallService.this, !isSystemContact || isAlwaysTurn);
                    WebRtcCallService.this.localCameraState = WebRtcCallService.this.peerConnection.getCameraState();
                    // A(当前)收到B的offer信令后，利用pc.setRemoteDescription()方法将B的SDP描述赋给A的PC对象。
                    WebRtcCallService.this.peerConnection.setRemoteDescription(new SessionDescription(SessionDescription.Type.OFFER, offer));
                    WebRtcCallService.this.lockManager.updatePhoneState(LockManager.PhoneState.PROCESSING);

                    // A调用pc.setLocalDescription将answer信令（SDP描述符）赋给自己的PC对象，同时将answer信令发送给B 。
                    SessionDescription sdp = WebRtcCallService.this.peerConnection.createAnswer(new MediaConstraints());
                    Log.i(TAG, "Answer SDP: " + sdp.description);
                    WebRtcCallService.this.peerConnection.setLocalDescription(sdp);

                    // 将answer信令发送给B
                    ListenableFutureTask<Boolean> listenableFutureTask = sendMessage(recipient, SignalServiceCallMessage.forAnswer(new AnswerMessage(WebRtcCallService.this.callId, sdp.description)));

                    // 添加ICE服务器候选地址
                    for (IceCandidate candidate : pendingIncomingIceUpdates)
                        WebRtcCallService.this.peerConnection.addIceCandidate(candidate);
                    WebRtcCallService.this.pendingIncomingIceUpdates = null;

                    // 通话消息发送失败，插入未接来电
                    listenableFutureTask.addListener(new FailureListener<Boolean>(WebRtcCallService.this.callState, WebRtcCallService.this.callId) {
                        @Override
                        public void onFailureContinue(Throwable error) {
                            Log.w(TAG, error);
                            insertMissedCall(recipient, true);
                            terminate();
                        }
                    });

                    // 发送EventBus时间做对应操作
                    if (recipient != null) {
                        sendMessage(viewModelStateFor(callState), recipient, localCameraState, remoteVideoEnabled, bluetoothAvailable, microphoneEnabled);
                    }
                } catch (PeerConnectionException e) {
                    Log.w(TAG, e);
                    terminate();
                }
            }
        });
    }

    /**
     * 处理去电
     *
     * @param intent
     */
    private void handleOutgoingCall(Intent intent) {
        Log.i(TAG, "handleOutgoingCall...");

        if (callState != CallState.STATE_IDLE)
            throw new IllegalStateException("Dialing from non-idle?"); // 非空闲状态

        try {
            this.callState = CallState.STATE_DIALING;                             // 状态设为拨号
            this.recipient = getRemoteRecipient(intent);                          // 获取盐城收件人
            this.callId = SecureRandom.getInstance("SHA1PRNG").nextLong();     // 生成CallId
            this.pendingOutgoingIceUpdates = new LinkedList<>();

            initializeVideo();      // 初始化视频资源

            // 发送EventBus时间做相关处理
            sendMessage(WebRtcViewModel.State.CALL_OUTGOING, recipient, localCameraState, remoteVideoEnabled, bluetoothAvailable, microphoneEnabled);
            lockManager.updatePhoneState(LockManager.PhoneState.IN_CALL);     // 更新锁屏对话状态为通话中
            audioManager.initializeAudioForCall();
            audioManager.startOutgoingRinger(OutgoingRinger.Type.SONAR);      // 播放去电铃声
            bluetoothStateManager.setWantsConnection(true);                   // 开启蓝牙


            setCallInProgressNotification(TYPE_OUTGOING_RINGING, recipient);   // 通知栏显示去电状态
            DatabaseFactory.getSmsDatabase(this).insertOutgoingCall(recipient.getAddress());  // 插入去电数据

            timeoutExecutor.schedule(new TimeoutRunnable(this.callId), 2, TimeUnit.MINUTES);  // 通话心跳

            retrieveTurnServers().addListener(new SuccessOnlyListener<List<PeerConnection.IceServer>>(this.callState, this.callId) {
                @Override
                public void onSuccessContinue(List<PeerConnection.IceServer> result) {
                    try {
                        boolean isAlwaysTurn = TextSecurePreferences.isTurnOnly(WebRtcCallService.this);

                        // 每一个通话都创建一个新的节点连接
                        WebRtcCallService.this.peerConnection = new PeerConnectionWrapper(WebRtcCallService.this, peerConnectionFactory, WebRtcCallService.this, localRenderer, result, WebRtcCallService.this, isAlwaysTurn);
                        WebRtcCallService.this.localCameraState = WebRtcCallService.this.peerConnection.getCameraState();
                        // 创建数据通道，用于传输媒体数据
                        WebRtcCallService.this.dataChannel = WebRtcCallService.this.peerConnection.createDataChannel(DATA_CHANNEL_NAME);
                        WebRtcCallService.this.dataChannel.registerObserver(WebRtcCallService.this);  // 注册观察者用于监听媒体数据传输窗台


                        // 调用pc.createOffer()方法创建一个包含SDP描述符（包含媒体信息，如分辨率、编解码能力等）的offer信令
                        SessionDescription sdp = WebRtcCallService.this.peerConnection.createOffer(new MediaConstraints());
                        // B会通过pc.setLocalDescription将offer信令（SDP描述符）赋给自己的PC对象，同时将offer信令发送给A
                        WebRtcCallService.this.peerConnection.setLocalDescription(sdp);

                        Log.i(TAG, "Sending offer: " + sdp.description);

                        // 将offer信令发送给对方
                        ListenableFutureTask<Boolean> listenableFutureTask = sendMessage(recipient, SignalServiceCallMessage.forOffer(new OfferMessage(WebRtcCallService.this.callId, sdp.description)));

                        // offer信令发送失败
                        listenableFutureTask.addListener(new FailureListener<Boolean>(callState, callId) {
                            @Override
                            public void onFailureContinue(Throwable error) {
                                Log.w(TAG, error);

                                if (error instanceof UntrustedIdentityException) {
                                    // 身份验证失败
                                    sendMessage(WebRtcViewModel.State.UNTRUSTED_IDENTITY, recipient, ((UntrustedIdentityException) error).getIdentityKey(), localCameraState, remoteVideoEnabled, bluetoothAvailable, microphoneEnabled);
                                } else if (error instanceof UnregisteredUserException) {
                                    // 没有此用户
                                    sendMessage(WebRtcViewModel.State.NO_SUCH_USER, recipient, localCameraState, remoteVideoEnabled, bluetoothAvailable, microphoneEnabled);
                                } else if (error instanceof IOException) {
                                    // 网络错误
                                    sendMessage(WebRtcViewModel.State.NETWORK_FAILURE, recipient, localCameraState, remoteVideoEnabled, bluetoothAvailable, microphoneEnabled);
                                }

                                terminate();
                            }
                        });

                        if (recipient != null) {
                            sendMessage(viewModelStateFor(callState), recipient, localCameraState, remoteVideoEnabled, bluetoothAvailable, microphoneEnabled);
                        }
                    } catch (PeerConnectionException e) {
                        Log.w(TAG, e);
                        terminate();
                    }
                }
            });
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private void handleResponseMessage(Intent intent) {
        try {
            Log.i(TAG, "Got response: " + intent.getStringExtra(EXTRA_REMOTE_DESCRIPTION));

            if (callState != CallState.STATE_DIALING || !getRemoteRecipient(intent).equals(recipient) || !Util.isEquals(this.callId, getCallId(intent))) {
                Log.w(TAG, "Got answer for recipient and call id we're not currently dialing: " + getCallId(intent) + ", " + getRemoteRecipient(intent));
                return;
            }

            if (peerConnection == null || pendingOutgoingIceUpdates == null) {
                throw new AssertionError("assert");
            }

            if (!pendingOutgoingIceUpdates.isEmpty()) {
                ListenableFutureTask<Boolean> listenableFutureTask = sendMessage(recipient, SignalServiceCallMessage.forIceUpdates(pendingOutgoingIceUpdates));

                listenableFutureTask.addListener(new FailureListener<Boolean>(callState, callId) {
                    @Override
                    public void onFailureContinue(Throwable error) {
                        Log.w(TAG, error);
                        sendMessage(WebRtcViewModel.State.NETWORK_FAILURE, recipient, localCameraState, remoteVideoEnabled, bluetoothAvailable, microphoneEnabled);

                        terminate();
                    }
                });
            }

            // B收到A的answer信令后，利用pc.setRemoteDescription()方法将A的SDP描述赋给B的PC对象。
            this.peerConnection.setRemoteDescription(new SessionDescription(SessionDescription.Type.ANSWER, intent.getStringExtra(EXTRA_REMOTE_DESCRIPTION)));
            this.pendingOutgoingIceUpdates = null;
        } catch (PeerConnectionException e) {
            Log.w(TAG, e);
            terminate();
        }
    }

    private void handleRemoteIceCandidate(Intent intent) {
        Log.i(TAG, "handleRemoteIceCandidate...");

        if (Util.isEquals(this.callId, getCallId(intent))) {
            IceCandidate candidate = new IceCandidate(intent.getStringExtra(EXTRA_ICE_SDP_MID),
                    intent.getIntExtra(EXTRA_ICE_SDP_LINE_INDEX, 0),
                    intent.getStringExtra(EXTRA_ICE_SDP));

            if (peerConnection != null) peerConnection.addIceCandidate(candidate);
            else if (pendingIncomingIceUpdates != null) pendingIncomingIceUpdates.add(candidate);
        }
    }

    /**
     * A、B收到对方发来的candidate信令后，利用pc.addIceCandidate()方法将穿透信息赋给各自的PeerConnection对象。
     * @param intent
     */
    private void handleLocalIceCandidate(Intent intent) {
        if (callState == CallState.STATE_IDLE || !Util.isEquals(this.callId, getCallId(intent))) {
            Log.w(TAG, "State is now idle, ignoring ice candidate...");
            return;
        }

        if (recipient == null || callId == null) {
            throw new AssertionError("assert: " + callState + ", " + callId);
        }

        IceUpdateMessage iceUpdateMessage = new IceUpdateMessage(this.callId, intent.getStringExtra(EXTRA_ICE_SDP_MID),
                intent.getIntExtra(EXTRA_ICE_SDP_LINE_INDEX, 0),
                intent.getStringExtra(EXTRA_ICE_SDP));

        if (pendingOutgoingIceUpdates != null) {
            Log.i(TAG, "Adding to pending ice candidates...");
            this.pendingOutgoingIceUpdates.add(iceUpdateMessage);
            return;
        }

        ListenableFutureTask<Boolean> listenableFutureTask = sendMessage(recipient, SignalServiceCallMessage.forIceUpdate(iceUpdateMessage));

        listenableFutureTask.addListener(new FailureListener<Boolean>(callState, callId) {
            @Override
            public void onFailureContinue(Throwable error) {
                Log.w(TAG, error);
                sendMessage(WebRtcViewModel.State.NETWORK_FAILURE, recipient, localCameraState, remoteVideoEnabled, bluetoothAvailable, microphoneEnabled);

                terminate();
            }
        });
    }

    private void handleIceConnected(Intent intent) {
        if (callState == CallState.STATE_ANSWERING) {
            if (this.recipient == null) throw new AssertionError("assert");

            this.callState = CallState.STATE_LOCAL_RINGING;
            this.lockManager.updatePhoneState(LockManager.PhoneState.INTERACTIVE);

            sendMessage(WebRtcViewModel.State.CALL_INCOMING, recipient, localCameraState, remoteVideoEnabled, bluetoothAvailable, microphoneEnabled);
            startCallCardActivity();
            audioManager.initializeAudioForCall();

            if (TextSecurePreferences.isCallNotificationsEnabled(this)) {
                Uri ringtone = recipient.resolve().getCallRingtone();
                VibrateState vibrateState = recipient.resolve().getCallVibrate();

                if (ringtone == null)
                    ringtone = TextSecurePreferences.getCallNotificationRingtone(this);

                audioManager.startIncomingRinger(ringtone, vibrateState == VibrateState.ENABLED || (vibrateState == VibrateState.DEFAULT && TextSecurePreferences.isCallNotificationVibrateEnabled(this)));
            }

            registerPowerButtonReceiver();

            setCallInProgressNotification(TYPE_INCOMING_RINGING, recipient);
        } else if (callState == CallState.STATE_DIALING) {
            if (this.recipient == null) throw new AssertionError("assert");

            this.callState = CallState.STATE_REMOTE_RINGING;
            this.audioManager.startOutgoingRinger(OutgoingRinger.Type.RINGING);

            sendMessage(WebRtcViewModel.State.CALL_RINGING, recipient, localCameraState, remoteVideoEnabled, bluetoothAvailable, microphoneEnabled);
        }
    }

    private void handleCallConnected(Intent intent) {
        if (callState != CallState.STATE_REMOTE_RINGING && callState != CallState.STATE_LOCAL_RINGING) {
            Log.w(TAG, "Ignoring call connected for unknown state: " + callState);
            return;
        }

        if (!Util.isEquals(this.callId, getCallId(intent))) {
            Log.w(TAG, "Ignoring connected for unknown call id: " + getCallId(intent));
            return;
        }

        if (recipient == null || peerConnection == null || dataChannel == null) {
            throw new AssertionError("assert");
        }

        audioManager.startCommunication(callState == CallState.STATE_REMOTE_RINGING);
        bluetoothStateManager.setWantsConnection(true);

        callState = CallState.STATE_CONNECTED;

        if (localCameraState.isEnabled())
            lockManager.updatePhoneState(LockManager.PhoneState.IN_VIDEO);
        else lockManager.updatePhoneState(LockManager.PhoneState.IN_CALL);

        sendMessage(WebRtcViewModel.State.CALL_CONNECTED, recipient, localCameraState, remoteVideoEnabled, bluetoothAvailable, microphoneEnabled);

        unregisterPowerButtonReceiver();

        setCallInProgressNotification(TYPE_ESTABLISHED, recipient);

        this.peerConnection.setCommunicationMode();
        this.peerConnection.setAudioEnabled(microphoneEnabled);
        this.peerConnection.setVideoEnabled(localCameraState.isEnabled());

        this.dataChannel.send(new DataChannel.Buffer(ByteBuffer.wrap(Data.newBuilder()
                .setVideoStreamingStatus(WebRtcDataProtos.VideoStreamingStatus.newBuilder()
                        .setId(this.callId)
                        .setEnabled(localCameraState.isEnabled()))
                .build().toByteArray()), false));
    }

    private void handleBusyCall(Intent intent) {
        Recipient recipient = getRemoteRecipient(intent);
        long callId = getCallId(intent);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            switch (callState) {
                case STATE_DIALING:
                case STATE_REMOTE_RINGING:
                    setCallInProgressNotification(TYPE_OUTGOING_RINGING, this.recipient);
                    break;
                case STATE_IDLE:
                    setCallInProgressNotification(TYPE_INCOMING_CONNECTING, recipient);
                    break;
                case STATE_ANSWERING:
                    setCallInProgressNotification(TYPE_INCOMING_CONNECTING, this.recipient);
                    break;
                case STATE_LOCAL_RINGING:
                    setCallInProgressNotification(TYPE_INCOMING_RINGING, this.recipient);
                    break;
                case STATE_CONNECTED:
                    setCallInProgressNotification(TYPE_ESTABLISHED, this.recipient);
                    break;
                default:
                    throw new AssertionError();
            }
        }

        if (callState == CallState.STATE_IDLE) {
            stopForeground(true);
        }

        sendMessage(recipient, SignalServiceCallMessage.forBusy(new BusyMessage(callId)));
        insertMissedCall(getRemoteRecipient(intent), false);
    }

    private void handleBusyMessage(Intent intent) {
        Log.i(TAG, "handleBusyMessage...");

        final Recipient recipient = getRemoteRecipient(intent);
        final long callId = getCallId(intent);

        if (callState != CallState.STATE_DIALING || !Util.isEquals(this.callId, callId) || !recipient.equals(this.recipient)) {
            Log.w(TAG, "Got busy message for inactive session...");
            return;
        }

        sendMessage(WebRtcViewModel.State.CALL_BUSY, recipient, localCameraState, remoteVideoEnabled, bluetoothAvailable, microphoneEnabled);

        audioManager.startOutgoingRinger(OutgoingRinger.Type.BUSY);
        Util.runOnMainDelayed(new Runnable() {
            @Override
            public void run() {
                Intent intent = new Intent(WebRtcCallService.this, WebRtcCallService.class);
                intent.setAction(ACTION_LOCAL_HANGUP);
                intent.putExtra(EXTRA_CALL_ID, intent.getLongExtra(EXTRA_CALL_ID, -1));
                intent.putExtra(EXTRA_REMOTE_ADDRESS, intent.getStringExtra(EXTRA_REMOTE_ADDRESS));

                startService(intent);
            }
        }, WebRtcCallActivity.BUSY_SIGNAL_DELAY_FINISH);
    }

    private void handleCheckTimeout(Intent intent) {
        if (this.callId != null &&
                this.callId == intent.getLongExtra(EXTRA_CALL_ID, -1) &&
                this.callState != CallState.STATE_CONNECTED) {
            Log.w(TAG, "Timing out call: " + this.callId);
            sendMessage(WebRtcViewModel.State.CALL_DISCONNECTED, this.recipient, localCameraState, remoteVideoEnabled, bluetoothAvailable, microphoneEnabled);

            if (this.callState == CallState.STATE_ANSWERING || this.callState == CallState.STATE_LOCAL_RINGING) {
                insertMissedCall(this.recipient, true);
            }

            terminate();
        }
    }

    private void handleIsInCallQuery(Intent intent) {
        ResultReceiver resultReceiver = intent.getParcelableExtra(EXTRA_RESULT_RECEIVER);

        if (resultReceiver != null) {
            resultReceiver.send(callState != CallState.STATE_IDLE ? 1 : 0, null);
        }
    }

    /**
     * 插入未接来电数据，并在通知栏通知
     *
     * @param recipient
     * @param signal
     */
    private void insertMissedCall(@NonNull Recipient recipient, boolean signal) {
        Pair<Long, Long> messageAndThreadId = DatabaseFactory.getSmsDatabase(this).insertMissedCall(recipient.getAddress());
        MessageNotifier.updateNotification(this, messageAndThreadId.second, signal);
    }

    private void handleAnswerCall(Intent intent) {
        if (callState != CallState.STATE_LOCAL_RINGING) {
            Log.w(TAG, "Can only answer from ringing!");
            return;
        }

        if (peerConnection == null || dataChannel == null || recipient == null || callId == null) {
            throw new AssertionError("assert");
        }

        DatabaseFactory.getSmsDatabase(this).insertReceivedCall(recipient.getAddress());

        this.peerConnection.setAudioEnabled(true);
        this.peerConnection.setVideoEnabled(true);
        this.dataChannel.send(new DataChannel.Buffer(ByteBuffer.wrap(Data.newBuilder().setConnected(Connected.newBuilder().setId(this.callId)).build().toByteArray()), false));

        intent.putExtra(EXTRA_CALL_ID, callId);
        intent.putExtra(EXTRA_REMOTE_ADDRESS, recipient.getAddress());
        handleCallConnected(intent);
    }

    private void handleDenyCall(Intent intent) {
        if (callState != CallState.STATE_LOCAL_RINGING) {
            Log.w(TAG, "Can only deny from ringing!");
            return;
        }

        if (recipient == null || callId == null || dataChannel == null) {
            throw new AssertionError("assert");
        }

        this.dataChannel.send(new DataChannel.Buffer(ByteBuffer.wrap(Data.newBuilder().setHangup(Hangup.newBuilder().setId(this.callId)).build().toByteArray()), false));
        sendMessage(this.recipient, SignalServiceCallMessage.forHangup(new HangupMessage(this.callId)));

        DatabaseFactory.getSmsDatabase(this).insertMissedCall(recipient.getAddress());

        this.terminate();
    }

    private void handleLocalHangup(Intent intent) {
        if (this.dataChannel != null && this.recipient != null && this.callId != null) {
            this.accountManager.cancelInFlightRequests();
            this.messageSender.cancelInFlightRequests();

            this.dataChannel.send(new DataChannel.Buffer(ByteBuffer.wrap(Data.newBuilder().setHangup(Hangup.newBuilder().setId(this.callId)).build().toByteArray()), false));
            sendMessage(this.recipient, SignalServiceCallMessage.forHangup(new HangupMessage(this.callId)));
            sendMessage(WebRtcViewModel.State.CALL_DISCONNECTED, this.recipient, localCameraState, remoteVideoEnabled, bluetoothAvailable, microphoneEnabled);
        }

        terminate();
    }

    private void handleRemoteHangup(Intent intent) {
        if (!Util.isEquals(this.callId, getCallId(intent))) {
            Log.w(TAG, "hangup for non-active call...");
            return;
        }

        if (this.recipient == null) {
            throw new AssertionError("assert");
        }

        if (this.callState == CallState.STATE_DIALING || this.callState == CallState.STATE_REMOTE_RINGING) {
            sendMessage(WebRtcViewModel.State.RECIPIENT_UNAVAILABLE, this.recipient, localCameraState, remoteVideoEnabled, bluetoothAvailable, microphoneEnabled);
        } else {
            sendMessage(WebRtcViewModel.State.CALL_DISCONNECTED, this.recipient, localCameraState, remoteVideoEnabled, bluetoothAvailable, microphoneEnabled);
        }

        if (this.callState == CallState.STATE_ANSWERING || this.callState == CallState.STATE_LOCAL_RINGING) {
            insertMissedCall(this.recipient, true);
        }

        this.terminate();
    }

    private void handleSetMuteAudio(Intent intent) {
        boolean muted = intent.getBooleanExtra(EXTRA_MUTE, false);
        this.microphoneEnabled = !muted;

        if (this.peerConnection != null) {
            this.peerConnection.setAudioEnabled(this.microphoneEnabled);
        }
    }

    private void handleSetMuteVideo(Intent intent) {
        AudioManager audioManager = ServiceUtil.getAudioManager(this);
        boolean muted = intent.getBooleanExtra(EXTRA_MUTE, false);

        if (this.peerConnection != null) {
            this.peerConnection.setVideoEnabled(!muted);
            this.localCameraState = this.peerConnection.getCameraState();
        }

        if (this.callId != null && this.dataChannel != null) {
            this.dataChannel.send(new DataChannel.Buffer(ByteBuffer.wrap(Data.newBuilder()
                    .setVideoStreamingStatus(WebRtcDataProtos.VideoStreamingStatus.newBuilder()
                            .setId(this.callId)
                            .setEnabled(!muted))
                    .build().toByteArray()), false));
        }

        if (callState == CallState.STATE_CONNECTED) {
            if (localCameraState.isEnabled())
                this.lockManager.updatePhoneState(LockManager.PhoneState.IN_VIDEO);
            else this.lockManager.updatePhoneState(LockManager.PhoneState.IN_CALL);
        }

        if (localCameraState.isEnabled() &&
                !audioManager.isSpeakerphoneOn() &&
                !audioManager.isBluetoothScoOn() &&
                !audioManager.isWiredHeadsetOn()) {
            audioManager.setSpeakerphoneOn(true);
        }

        sendMessage(viewModelStateFor(callState), this.recipient, localCameraState, remoteVideoEnabled, bluetoothAvailable, microphoneEnabled);
    }

    private void handleSetCameraFlip(Intent intent) {
        Log.i(TAG, "handleSetCameraFlip()...");

        if (localCameraState.isEnabled() && peerConnection != null) {
            peerConnection.flipCamera();
            localCameraState = peerConnection.getCameraState();
            if (recipient != null) {
                sendMessage(viewModelStateFor(callState), recipient, localCameraState, remoteVideoEnabled, bluetoothAvailable, microphoneEnabled);
            }
        }
    }

    private void handleBluetoothChange(Intent intent) {
        this.bluetoothAvailable = intent.getBooleanExtra(EXTRA_AVAILABLE, false);

        if (recipient != null) {
            sendMessage(viewModelStateFor(callState), recipient, localCameraState, remoteVideoEnabled, bluetoothAvailable, microphoneEnabled);
        }
    }

    private void handleWiredHeadsetChange(Intent intent) {
        Log.i(TAG, "handleWiredHeadsetChange...");

        if (callState == CallState.STATE_CONNECTED ||
                callState == CallState.STATE_DIALING ||
                callState == CallState.STATE_REMOTE_RINGING) {
            AudioManager audioManager = ServiceUtil.getAudioManager(this);
            boolean present = intent.getBooleanExtra(EXTRA_AVAILABLE, false);

            if (present && audioManager.isSpeakerphoneOn()) {
                audioManager.setSpeakerphoneOn(false);
                audioManager.setBluetoothScoOn(false);
            } else if (!present && !audioManager.isSpeakerphoneOn() && !audioManager.isBluetoothScoOn() && localCameraState.isEnabled()) {
                audioManager.setSpeakerphoneOn(true);
            }

            if (recipient != null) {
                sendMessage(viewModelStateFor(callState), recipient, localCameraState, remoteVideoEnabled, bluetoothAvailable, microphoneEnabled);
            }
        }
    }

    private void handleScreenOffChange(Intent intent) {
        if (callState == CallState.STATE_ANSWERING ||
                callState == CallState.STATE_LOCAL_RINGING) {
            Log.i(TAG, "Silencing incoming ringer...");
            audioManager.silenceIncomingRinger();
        }
    }

    private void handleRemoteVideoMute(Intent intent) {
        boolean muted = intent.getBooleanExtra(EXTRA_MUTE, false);
        long callId = intent.getLongExtra(EXTRA_CALL_ID, -1);

        if (this.recipient == null || this.callState != CallState.STATE_CONNECTED || !Util.isEquals(this.callId, callId)) {
            Log.w(TAG, "Got video toggle for inactive call, ignoring...");
            return;
        }

        this.remoteVideoEnabled = !muted;
        sendMessage(WebRtcViewModel.State.CALL_CONNECTED, this.recipient, localCameraState, remoteVideoEnabled, bluetoothAvailable, microphoneEnabled);
    }

    /// Helper Methods

    private boolean isBusy() {
        TelephonyManager telephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);

        return callState != CallState.STATE_IDLE || telephonyManager.getCallState() != TelephonyManager.CALL_STATE_IDLE;
    }

    private boolean isIdle() {
        return callState == CallState.STATE_IDLE;
    }

    /**
     * 2分钟内未接来电
     *
     * @param intent
     * @return
     */
    private boolean isIncomingMessageExpired(Intent intent) {
        return System.currentTimeMillis() - intent.getLongExtra(WebRtcCallService.EXTRA_TIMESTAMP, -1) > TimeUnit.MINUTES.toMillis(2);
    }

    /**
     * 初始化视频资源
     */
    private void initializeVideo() {
        Util.runOnMainSync(new Runnable() {
            @Override
            public void run() {
                eglBase = EglBase.create();
                localRenderer = new SurfaceViewRenderer(WebRtcCallService.this);
                remoteRenderer = new SurfaceViewRenderer(WebRtcCallService.this);

                localRenderer.init(eglBase.getEglBaseContext(), null);
                remoteRenderer.init(eglBase.getEglBaseContext(), null);

                peerConnectionFactory.setVideoHwAccelerationOptions(eglBase.getEglBaseContext(),
                        eglBase.getEglBaseContext());
            }
        });
    }

    private void setCallInProgressNotification(int type, Recipient recipient) {
        startForeground(CallNotificationBuilder.WEBRTC_NOTIFICATION,
                CallNotificationBuilder.getCallInProgressNotification(this, type, recipient));
    }

    /**
     * 结束通话
     */
    private synchronized void terminate() {
        lockManager.updatePhoneState(LockManager.PhoneState.PROCESSING);
        stopForeground(true);                           // 停止通知栏显示

        audioManager.stop(callState == CallState.STATE_DIALING || callState == CallState.STATE_REMOTE_RINGING || callState == CallState.STATE_CONNECTED);
        bluetoothStateManager.setWantsConnection(false);

        if (peerConnection != null) {
            peerConnection.dispose();
            peerConnection = null;
        }

        if (eglBase != null && localRenderer != null && remoteRenderer != null) {
            localRenderer.release();
            remoteRenderer.release();
            eglBase.release();

            localRenderer = null;
            remoteRenderer = null;
            eglBase = null;
        }

        this.callState = CallState.STATE_IDLE;
        this.localCameraState = CameraState.UNKNOWN;
        this.recipient = null;
        this.callId = null;
        this.microphoneEnabled = true;
        this.remoteVideoEnabled = false;
        this.pendingOutgoingIceUpdates = null;
        this.pendingIncomingIceUpdates = null;
        lockManager.updatePhoneState(LockManager.PhoneState.IDLE);
    }


    private void sendMessage(@NonNull WebRtcViewModel.State state,
                             @NonNull Recipient recipient,
                             @NonNull CameraState localCameraState,
                             boolean remoteVideoEnabled,
                             boolean bluetoothAvailable,
                             boolean microphoneEnabled) {
        EventBus.getDefault().postSticky(new WebRtcViewModel(state, recipient, localCameraState, remoteVideoEnabled, bluetoothAvailable, microphoneEnabled));
    }

    private void sendMessage(@NonNull WebRtcViewModel.State state,
                             @NonNull Recipient recipient,
                             @NonNull IdentityKey identityKey,
                             @NonNull CameraState localCameraState,
                             boolean remoteVideoEnabled,
                             boolean bluetoothAvailable,
                             boolean microphoneEnabled) {
        EventBus.getDefault().postSticky(new WebRtcViewModel(state, recipient, identityKey, localCameraState, remoteVideoEnabled, bluetoothAvailable, microphoneEnabled));
    }

    /**
     * 调用WebSocket发送通话消息
     *
     * @param recipient
     * @param callMessage
     * @return
     */
    private ListenableFutureTask<Boolean> sendMessage(@NonNull final Recipient recipient,
                                                      @NonNull final SignalServiceCallMessage callMessage) {
        Callable<Boolean> callable = new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                messageSender.sendCallMessage(new SignalServiceAddress(recipient.getAddress().toPhoneString()),
                        UnidentifiedAccessUtil.getAccessFor(WebRtcCallService.this, recipient),
                        callMessage);
                return true;
            }
        };

        ListenableFutureTask<Boolean> listenableFutureTask = new ListenableFutureTask<>(callable, null, serviceExecutor);
        networkExecutor.execute(listenableFutureTask);

        return listenableFutureTask;
    }

    private void startCallCardActivity() {
        Intent activityIntent = new Intent();
        activityIntent.setClass(this, WebRtcCallActivity.class);
        activityIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        this.startActivity(activityIntent);
    }

    ///

    private @NonNull
    Recipient getRemoteRecipient(Intent intent) {
        Address remoteAddress = intent.getParcelableExtra(EXTRA_REMOTE_ADDRESS);
        if (remoteAddress == null) throw new AssertionError("No recipient in intent!");

        return Recipient.from(this, remoteAddress, true);
    }

    private long getCallId(Intent intent) {
        return intent.getLongExtra(EXTRA_CALL_ID, -1);
    }


    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /// PeerConnection Observer
    @Override
    public void onSignalingChange(PeerConnection.SignalingState newState) {
        Log.i(TAG, "onSignalingChange: " + newState);
    }

    @Override
    public void onIceConnectionChange(PeerConnection.IceConnectionState newState) {
        Log.i(TAG, "onIceConnectionChange:" + newState);

        if (newState == PeerConnection.IceConnectionState.CONNECTED ||
                newState == PeerConnection.IceConnectionState.COMPLETED) {
            Intent intent = new Intent(this, WebRtcCallService.class);
            intent.setAction(ACTION_ICE_CONNECTED);

            startService(intent);
        } else if (newState == PeerConnection.IceConnectionState.FAILED) {
            Intent intent = new Intent(this, WebRtcCallService.class);
            intent.setAction(ACTION_REMOTE_HANGUP);
            intent.putExtra(EXTRA_CALL_ID, this.callId);

            startService(intent);
        }
    }

    @Override
    public void onIceConnectionReceivingChange(boolean receiving) {
        Log.i(TAG, "onIceConnectionReceivingChange:" + receiving);
    }

    @Override
    public void onIceGatheringChange(PeerConnection.IceGatheringState newState) {
        Log.i(TAG, "onIceGatheringChange:" + newState);

    }

    /**
     * 当网络候选可用时，PeerConnection.Observer监听会调用onIceCandidate()响应函数并提供IceCandidate（里面包含穿透所需的信息）的对象。
     * 在这里，我们可以让A、B将IceCandidate对象的内容发送给对方。
     * @param candidate
     */
    @Override
    public void onIceCandidate(IceCandidate candidate) {
        Log.i(TAG, "onIceCandidate:" + candidate);
        Intent intent = new Intent(this, WebRtcCallService.class);

        intent.setAction(ACTION_ICE_CANDIDATE);
        intent.putExtra(EXTRA_ICE_SDP_MID, candidate.sdpMid);
        intent.putExtra(EXTRA_ICE_SDP_LINE_INDEX, candidate.sdpMLineIndex);
        intent.putExtra(EXTRA_ICE_SDP, candidate.sdp);
        intent.putExtra(EXTRA_CALL_ID, callId);

        startService(intent);
    }

    @Override
    public void onIceCandidatesRemoved(IceCandidate[] candidates) {
        Log.i(TAG, "onIceCandidatesRemoved:" + (candidates != null ? candidates.length : null));
    }

    /**
     * 在连接通道正常的情况下，对方的PeerConnection.Observer监听就会调用onAddStream()响应函数并提供接收到的媒体流。
     * @param stream
     */
    @Override
    public void onAddStream(MediaStream stream) {
        Log.i(TAG, "onAddStream:" + stream);

        for (AudioTrack audioTrack : stream.audioTracks) {
            audioTrack.setEnabled(true);
        }

        if (stream.videoTracks != null && stream.videoTracks.size() == 1) {
            VideoTrack videoTrack = stream.videoTracks.get(0);
            videoTrack.setEnabled(true);
            videoTrack.addSink(remoteRenderer);
        }
    }

    @Override
    public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {
        Log.i(TAG, "onAddTrack: " + mediaStreams);
    }

    @Override
    public void onRemoveStream(MediaStream stream) {
        Log.i(TAG, "onRemoveStream:" + stream);
    }

    @Override
    public void onDataChannel(DataChannel dataChannel) {
        Log.i(TAG, "onDataChannel:" + dataChannel.label());

        if (dataChannel.label().equals(DATA_CHANNEL_NAME)) {
            this.dataChannel = dataChannel;
            this.dataChannel.registerObserver(this);
        }
    }

    @Override
    public void onRenegotiationNeeded() {
        Log.i(TAG, "onRenegotiationNeeded");
        // TODO renegotiate
    }

    @Override
    public void onBufferedAmountChange(long l) {
        Log.i(TAG, "onBufferedAmountChange: " + l);
    }

    @Override
    public void onStateChange() {
        Log.i(TAG, "onStateChange");
    }

    @Override
    public void onMessage(DataChannel.Buffer buffer) {
        Log.i(TAG, "onMessage...");

        try {
            byte[] data = new byte[buffer.data.remaining()];
            buffer.data.get(data);

            Data dataMessage = Data.parseFrom(data);

            if (dataMessage.hasConnected()) {
                Log.i(TAG, "hasConnected...");
                Intent intent = new Intent(this, WebRtcCallService.class);
                intent.setAction(ACTION_CALL_CONNECTED);
                intent.putExtra(EXTRA_CALL_ID, dataMessage.getConnected().getId());
                startService(intent);
            } else if (dataMessage.hasHangup()) {
                Log.i(TAG, "hasHangup...");
                Intent intent = new Intent(this, WebRtcCallService.class);
                intent.setAction(ACTION_REMOTE_HANGUP);
                intent.putExtra(EXTRA_CALL_ID, dataMessage.getHangup().getId());
                startService(intent);
            } else if (dataMessage.hasVideoStreamingStatus()) {
                Log.i(TAG, "hasVideoStreamingStatus...");
                Intent intent = new Intent(this, WebRtcCallService.class);
                intent.setAction(ACTION_REMOTE_VIDEO_MUTE);
                intent.putExtra(EXTRA_CALL_ID, dataMessage.getVideoStreamingStatus().getId());
                intent.putExtra(EXTRA_MUTE, !dataMessage.getVideoStreamingStatus().getEnabled());
                startService(intent);
            }
        } catch (InvalidProtocolBufferException e) {
            Log.w(TAG, e);
        }
    }

    /**
     * 检索中继器服务器地址
     *
     * @return
     */
    private ListenableFutureTask<List<PeerConnection.IceServer>> retrieveTurnServers() {
        Callable<List<PeerConnection.IceServer>> callable = () -> {
            LinkedList<PeerConnection.IceServer> results = new LinkedList<>();

            try {
                TurnServerInfo turnServerInfo = accountManager.getTurnServerInfo();   // 获取当前账号的中继器服务器地址信息

                for (String url : turnServerInfo.getUrls()) {
                    if (url.startsWith("turn")) {   // 如果包含turn前缀，则构建ICE服务器时传入用户名和密码
                        results.add(new PeerConnection.IceServer(url, turnServerInfo.getUsername(), turnServerInfo.getPassword()));
                    } else {
                        results.add(new PeerConnection.IceServer(url));
                    }
                }

            } catch (IOException e) {
                Log.w(TAG, e);
            }

            return results;
        };

        // Future模式
        ListenableFutureTask<List<PeerConnection.IceServer>> futureTask = new ListenableFutureTask<>(callable, null, serviceExecutor);
        networkExecutor.execute(futureTask);

        return futureTask;
    }

    ////

    private WebRtcViewModel.State viewModelStateFor(CallState state) {
        switch (state) {
            case STATE_CONNECTED:
                return WebRtcViewModel.State.CALL_CONNECTED;
            case STATE_DIALING:
                return WebRtcViewModel.State.CALL_OUTGOING;
            case STATE_REMOTE_RINGING:
                return WebRtcViewModel.State.CALL_RINGING;
            case STATE_LOCAL_RINGING:
                return WebRtcViewModel.State.CALL_INCOMING;
            case STATE_ANSWERING:
                return WebRtcViewModel.State.CALL_INCOMING;
            case STATE_IDLE:
                return WebRtcViewModel.State.CALL_DISCONNECTED;
        }

        return WebRtcViewModel.State.CALL_DISCONNECTED;
    }

    ///

    private static class WiredHeadsetStateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            int state = intent.getIntExtra("state", -1);

            Intent serviceIntent = new Intent(context, WebRtcCallService.class);
            serviceIntent.setAction(WebRtcCallService.ACTION_WIRED_HEADSET_CHANGE);
            serviceIntent.putExtra(WebRtcCallService.EXTRA_AVAILABLE, state != 0);
            context.startService(serviceIntent);
        }
    }

    private static class PowerButtonReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                Intent serviceIntent = new Intent(context, WebRtcCallService.class);
                serviceIntent.setAction(WebRtcCallService.ACTION_SCREEN_OFF);
                context.startService(serviceIntent);
            }
        }
    }

    /**
     * 通话心跳机制，每隔2分钟执行超时检查
     */
    private class TimeoutRunnable implements Runnable {

        private final long callId;

        private TimeoutRunnable(long callId) {
            this.callId = callId;
        }

        public void run() {
            Intent intent = new Intent(WebRtcCallService.this, WebRtcCallService.class);
            intent.setAction(WebRtcCallService.ACTION_CHECK_TIMEOUT);
            intent.putExtra(EXTRA_CALL_ID, callId);
            startService(intent);
        }
    }

    private static class ProximityLockRelease implements Thread.UncaughtExceptionHandler {
        private final LockManager lockManager;

        private ProximityLockRelease(LockManager lockManager) {
            this.lockManager = lockManager;
        }

        @Override
        public void uncaughtException(Thread thread, Throwable throwable) {
            Log.d(TAG, "Uncaught exception - releasing proximity lock", throwable);
            lockManager.updatePhoneState(LockManager.PhoneState.IDLE);
        }
    }

    private abstract class StateAwareListener<V> implements FutureTaskListener<V> {

        private final CallState expectedState;
        private final long expectedCallId;

        StateAwareListener(CallState expectedState, long expectedCallId) {
            this.expectedState = expectedState;
            this.expectedCallId = expectedCallId;
        }


        @Override
        public void onSuccess(V result) {
            if (!isConsistentState()) {
                Log.w(TAG, "State has changed since request, aborting success callback...");
            } else {
                onSuccessContinue(result);
            }
        }

        @Override
        public void onFailure(ExecutionException throwable) {
            if (!isConsistentState()) {
                Log.w(TAG, throwable);
                Log.w(TAG, "State has changed since request, aborting failure callback...");
            } else {
                onFailureContinue(throwable.getCause());
            }
        }

        private boolean isConsistentState() {
            return this.expectedState == callState && Util.isEquals(callId, this.expectedCallId);
        }

        public abstract void onSuccessContinue(V result);

        public abstract void onFailureContinue(Throwable throwable);
    }

    private abstract class FailureListener<V> extends StateAwareListener<V> {
        FailureListener(CallState expectedState, long expectedCallId) {
            super(expectedState, expectedCallId);
        }

        @Override
        public void onSuccessContinue(V result) {
        }
    }

    private abstract class SuccessOnlyListener<V> extends StateAwareListener<V> {
        SuccessOnlyListener(CallState expectedState, long expectedCallId) {
            super(expectedState, expectedCallId);
        }

        @Override
        public void onFailureContinue(Throwable throwable) {
            Log.w(TAG, throwable);
            throw new AssertionError(throwable);
        }
    }

    @WorkerThread
    public static boolean isCallActive(Context context) {
        Log.i(TAG, "isCallActive()");

        HandlerThread handlerThread = null;

        try {
            handlerThread = new HandlerThread("webrtc-callback");
            handlerThread.start();

            final SettableFuture<Boolean> future = new SettableFuture<>();

            ResultReceiver resultReceiver = new ResultReceiver(new Handler(handlerThread.getLooper())) {
                protected void onReceiveResult(int resultCode, Bundle resultData) {
                    Log.i(TAG, "Got result...");
                    future.set(resultCode == 1);
                }
            };

            Intent intent = new Intent(context, WebRtcCallService.class);
            intent.setAction(ACTION_IS_IN_CALL_QUERY);
            intent.putExtra(EXTRA_RESULT_RECEIVER, resultReceiver);

            context.startService(intent);

            Log.i(TAG, "Blocking on result...");
            return future.get();
        } catch (InterruptedException | ExecutionException e) {
            Log.w(TAG, e);
            return false;
        } finally {
            if (handlerThread != null) handlerThread.quit();
        }
    }

    public static void isCallActive(Context context, ResultReceiver resultReceiver) {
        Intent intent = new Intent(context, WebRtcCallService.class);
        intent.setAction(ACTION_IS_IN_CALL_QUERY);
        intent.putExtra(EXTRA_RESULT_RECEIVER, resultReceiver);

        context.startService(intent);
    }
}

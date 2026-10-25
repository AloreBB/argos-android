package com.termux.terminal;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * A terminal session coupled to a REMOTE byte stream (an SSH PTY channel) instead of a local
 * subprocess.
 * <p>
 * Argos modification of the original Termux class: the JNI subprocess, its file descriptor and the
 * reader/writer/waiter threads are gone. The owner provides a {@link Transport} that receives what
 * the user types and the window size, and pushes remote output with {@link #appendRemoteOutput}.
 * The public surface used by {@code TerminalView} (write, writeCodePoint, updateSize, getEmulator…)
 * is kept so the view works unchanged.
 * <p>
 * All terminal emulation and client callbacks run on the main thread.
 */
public final class TerminalSession extends TerminalOutput {

    /** Sink for everything that goes from the terminal to the remote PTY. */
    public interface Transport {
        /** Bytes typed by the user (already encoded). Called on the main thread; must not block. */
        void write(byte[] data, int offset, int count);

        /** New PTY size in cells. Called on the main thread; must not block. */
        void resize(int columns, int rows);
    }

    private static final int MSG_NEW_INPUT = 1;
    private static final int MSG_FINISHED = 4;

    public final String mHandle = UUID.randomUUID().toString();

    TerminalEmulator mEmulator;

    /**
     * Written by the network reader thread and drained by the main thread into the emulator.
     * {@link ByteQueue#write} blocks while full, which gives natural backpressure to the reader.
     */
    final ByteQueue mRemoteToTerminalIOQueue = new ByteQueue(64 * 1024);

    /** Buffer to translate code points into utf8 before handing them to the transport. */
    private final byte[] mUtf8InputBuffer = new byte[5];

    /** Callback which gets notified when a session finishes or changes title. */
    TerminalSessionClient mClient;

    private final Transport mTransport;
    private final Integer mTranscriptRows;

    private volatile boolean mFinished;

    /** Set by the application for user identification of session, not by terminal. */
    public String mSessionName;

    final Handler mMainThreadHandler = new MainThreadHandler();

    public TerminalSession(Integer transcriptRows, TerminalSessionClient client, Transport transport) {
        this.mTranscriptRows = transcriptRows;
        this.mClient = client;
        this.mTransport = transport;
    }

    /**
     * @param client The {@link TerminalSessionClient} interface implementation to allow
     *               for communication between {@link TerminalSession} and its client.
     */
    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;

        if (mEmulator != null)
            mEmulator.updateTerminalSessionClient(client);
    }

    /** Inform the remote pty of the new size and reflow or initialize the emulator. */
    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (mEmulator == null) {
            initializeEmulator(columns, rows, cellWidthPixels, cellHeightPixels);
        } else {
            mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels);
        }
        mTransport.resize(columns, rows);
    }

    /** The terminal title as set through escape sequences or null if none set. */
    public String getTitle() {
        return (mEmulator == null) ? null : mEmulator.getTitle();
    }

    /** Create the emulator with the given window size. Remote output is only consumed after this. */
    public void initializeEmulator(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        mEmulator = new TerminalEmulator(this, columns, rows, cellWidthPixels, cellHeightPixels, mTranscriptRows, mClient);
        // Output that arrived before the emulator existed is waiting in the queue.
        mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
    }

    /**
     * Push bytes received from the remote PTY. Safe to call from any thread; blocks while the
     * queue is full. Returns false once the session has been finished.
     */
    public boolean appendRemoteOutput(byte[] data, int offset, int count) {
        if (mFinished) return false;
        if (!mRemoteToTerminalIOQueue.write(data, offset, count)) return false;
        mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
        return true;
    }

    /**
     * Mark the remote side as gone. Safe to call from any thread. {@code message}, if not null, is
     * printed in the terminal after any pending output.
     */
    public void finish(String message) {
        if (mFinished) return;
        mFinished = true;
        mMainThreadHandler.sendMessage(mMainThreadHandler.obtainMessage(MSG_FINISHED, message));
    }

    /** Write data to the remote pty. */
    @Override
    public void write(byte[] data, int offset, int count) {
        if (!mFinished) mTransport.write(data, offset, count);
    }

    /** Write the Unicode code point to the terminal encoded in UTF-8. */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            // 1114111 (= 2**16 + 1024**2 - 1) is the highest code point, [0xD800,0xDFFF] is the surrogate range.
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }

        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;

        if (codePoint <= /* 7 bits */0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= /* 11 bits */0b11111111111) {
            /* 110xxxxx leading byte with leading 5 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= /* 16 bits */0b1111111111111111) {
            /* 1110xxxx leading byte with leading 4 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else { /* We have checked codePoint <= 1114111 above, so we have max 21 bits = 0b111111111111111111111 */
            /* 11110xxx leading byte with leading 3 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    /** Notify the {@link #mClient} that the screen has changed. */
    protected void notifyScreenUpdate() {
        mClient.onTextChanged(this);
    }

    /** Reset state for terminal emulator state. */
    public void reset() {
        mEmulator.reset();
        notifyScreenUpdate();
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        mClient.onTitleChanged(this);
    }

    public boolean isRunning() {
        return !mFinished;
    }

    /** Called by the emulator for OSC 52, i.e. remote-initiated. */
    @Override
    public void onCopyTextToClipboard(String text) {
        mClient.onRemoteClipboardCopy(this, text);
    }

    /** Called by the text selection UI when the user copies locally. */
    public void copyLocalSelection(String text) {
        mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    public void onPasteTextFromClipboard() {
        mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        mClient.onColorsChanged(this);
    }

    @SuppressLint("HandlerLeak")
    class MainThreadHandler extends Handler {

        final byte[] mReceiveBuffer = new byte[64 * 1024];

        MainThreadHandler() {
            super(Looper.getMainLooper());
        }

        @Override
        public void handleMessage(Message msg) {
            // Until the view reports a size there is no emulator: keep output queued.
            if (mEmulator == null) {
                if (msg.what == MSG_FINISHED) {
                    mRemoteToTerminalIOQueue.close();
                    mClient.onSessionFinished(TerminalSession.this);
                }
                return;
            }

            int bytesRead = mRemoteToTerminalIOQueue.read(mReceiveBuffer, false);
            if (bytesRead > 0) {
                mEmulator.append(mReceiveBuffer, bytesRead);
                notifyScreenUpdate();
            }

            if (msg.what == MSG_FINISHED) {
                // Drain whatever is still queued before printing the final message.
                while ((bytesRead = mRemoteToTerminalIOQueue.read(mReceiveBuffer, false)) > 0) {
                    mEmulator.append(mReceiveBuffer, bytesRead);
                }
                mRemoteToTerminalIOQueue.close();
                String message = (String) msg.obj;
                if (message != null) {
                    byte[] bytesToWrite = ("\r\n" + message).getBytes(StandardCharsets.UTF_8);
                    mEmulator.append(bytesToWrite, bytesToWrite.length);
                }
                notifyScreenUpdate();
                mClient.onSessionFinished(TerminalSession.this);
            }
        }

    }

}

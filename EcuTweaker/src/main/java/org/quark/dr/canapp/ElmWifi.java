package org.quark.dr.canapp;

import android.app.AlertDialog;
import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;

public class ElmWifi extends ElmBase{
    private static final String TAG = "ElmWifi";
    private final Context mContext;
    private final HandlerThread mOBDThread;
    private final Handler mWIFIHandler;
    private Socket mSocket;
    private boolean mConnecting = false;
    private WifiManager.WifiLock wifiLock;
    private int mState;

    OutputStream outStream;
    InputStream inStream;

    InetAddress serverAddr = null;
    String serverIpAddress = "192.168.0.10";
    public static final int SERVERPORT = 35000;
    String deviceName = "Elm327";
    private ElmWifi.ConnectedThread mConnectedThread;

    private class SocketTask extends AsyncTask<Void, Void, Boolean> {        ;
        IOException ioException;
        Context context;
        SocketTask(Context context) {
            super();
            this.ioException = null;
            this.context = context;
        }
        @Override
        protected Boolean doInBackground(Void... params) {

            try {
                mSocket = new Socket();
                mSocket.connect(new InetSocketAddress(serverIpAddress, SERVERPORT), 5000);
                mSocket.setKeepAlive(true);
                setState(STATE_CONNECTED);
                mConnecting = false;

                // Send the name of the connected device back to the UI Activity
                Message msg = mWIFIHandler.obtainMessage(ScreenActivity.MESSAGE_DEVICE_NAME);
                Bundle bundle = new Bundle();
                bundle.putString(ScreenActivity.DEVICE_NAME, deviceName);
                msg.setData(bundle);
                mWIFIHandler.sendMessage(msg);

                if (mConnectedThread != null) {
                    mConnectedThread.cancel();
                    mConnectedThread = null;
                }

                // Start the thread to manage the connection and perform transmissions
                mConnectedThread = new ElmWifi.ConnectedThread(mSocket);
                mConnectedThread.start();

                return true;
            } catch (IOException e) {
                this.ioException = e;
                connectionFailed();
                return false;
            }
        }
        @Override
        protected void onPostExecute(Boolean result) {

            if (this.ioException != null) {
                new AlertDialog.Builder(context)
                        .setTitle("An error occurred")
                        .setMessage(this.ioException.toString())
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .show();
            }
            else
            {
                Toast.makeText(context,"Elm327 wifi connected...", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void connectionFailed() {
        // Send a failure message back to the Activity
        Message msg = mWIFIHandler.obtainMessage(ScreenActivity.MESSAGE_TOAST);
        Bundle bundle = new Bundle();
        bundle.putString(ScreenActivity.TOAST, "Unable to connect wifi device");
        msg.setData(bundle);
        mWIFIHandler.sendMessage(msg);
        setState(STATE_NONE);
    }

    private void connectionLost() {
        // Send a failure message back to the Activity
        Message msg = mWIFIHandler.obtainMessage(ScreenActivity.MESSAGE_TOAST);
        Bundle bundle = new Bundle();
        bundle.putString(ScreenActivity.TOAST, "Wifi device connection was lost");
        msg.setData(bundle);
        mWIFIHandler.sendMessage(msg);
        setState(STATE_NONE);
    }

    private Runnable mConnectRunnable = new Runnable() {
        @Override
        public void run() {

            if(!isConnected() && mConnecting)
            {

                SocketTask task = new SocketTask(mContext);
                task.execute();
            }

            mWIFIHandler.postDelayed(mConnectRunnable, 10000);
        }
    };

    public ElmWifi(Context context, Handler handler, String logDir) {
        super(handler, logDir);
        this.mContext = context;
        mOBDThread = new HandlerThread("OBDII", Thread.NORM_PRIORITY);
        mOBDThread.start();
        mWIFIHandler = handler;
    }

    @Override
    public synchronized int getState() {
            return mState;
    }

    private synchronized void setState(int state) {
        mState = state;
        // Give the new state to the Handler so the UI Activity can update
        mWIFIHandler.obtainMessage(ScreenActivity.MESSAGE_STATE_CHANGE, state, -1).sendToTarget();
    }

    public boolean connect(String address) {
        if (!address.isEmpty()) {
            serverIpAddress = address;
        }

        if (mConnecting || isConnected()) {
            return false;
        }

        setState(STATE_CONNECTING);

        WifiManager wifi = (WifiManager) mContext.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifiLock == null) {
            this.wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "HighPerf wifi lock");
        }

        wifiLock.acquire();
        WifiInfo wifiInfo = wifi.getConnectionInfo();
        String name = wifiInfo.getSSID();

        if (wifi.isWifiEnabled() && (name.toUpperCase().contains("OBD") ||
                name.toUpperCase().contains("ELM") ||
                name.toUpperCase().contains("ECU") ||
                name.toUpperCase().contains("LINK") ) ) {
            mConnecting = true;
            deviceName = name.replace("\"","");

            mWIFIHandler.removeCallbacksAndMessages(null);
            mWIFIHandler.post(mConnectRunnable);

            return true;
        }

        Message msg = mWIFIHandler.obtainMessage(ScreenActivity.MESSAGE_TOAST);
        Bundle bundle = new Bundle();
        bundle.putString(ScreenActivity.TOAST, "Unable to connect wifi device");
        msg.setData(bundle);
        mWIFIHandler.sendMessage(msg);

        setState(STATE_NONE);

        mConnecting = false;
        return false;
    }

    @Override
    public void disconnect() {
        mConnectedThread.cancel();
        if (wifiLock != null && wifiLock.isHeld())
            wifiLock.release();

        mWIFIHandler.removeCallbacksAndMessages(null);
        mConnecting = false;
        mWIFIHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mSocket != null && mSocket.isConnected()) {
                    try {
                        mSocket.close();
                        mSocket = null;
                        setState(STATE_NONE);
                    } catch (Exception e) {
                        Log.d(TAG, "disconnect: " + Log.getStackTraceString(e));
                    }
                }
            }
        });
    }

    public boolean isConnected() {
        return (mSocket != null && mSocket.isConnected());
    }

    @Override
    protected String write_raw(String raw_buffer) {
        return mConnectedThread.write(raw_buffer.getBytes());
    }

    private class ConnectedThread extends Thread {
        private final Socket mmSocket;

        public ConnectedThread(Socket socket) {
            mmessages.clear();
            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            // Get the BluetoothSocket input and output streams
            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                Log.e(TAG, "temp sockets not created", e);
            }

            inStream = tmpIn;
            outStream = tmpOut;
        }

        public void run() {
            main_loop();
        }

        public String write(byte[] buffer) {
            writeDataToOBD(buffer);
            return readDataFromOBD();
        }

        public void writeDataToOBD(byte[] buffer) {
            try {
                if(mSocket != null)
                {
                    outStream = mSocket.getOutputStream();
                    byte[] arrayOfBytes = buffer;
                    outStream.write(arrayOfBytes);
                    outStream.flush();
                }
            } catch (Exception localIOException1) {
                localIOException1.printStackTrace();
                connectionLost();
            }
        }

        public String readDataFromOBD() {

            while (true) {
                try {
                    if(mSocket != null)
                    {
                        String rawData;
                        byte b;
                        StringBuilder res = new StringBuilder();
                        inStream = mSocket.getInputStream();

                        long start = System.currentTimeMillis();
                        while ((char) (b = (byte) inStream.read()) != '>') {
                            if (b == 0x0d)
                                b = 0x0a;
                            res.append((char) b);
                        }
                        rawData = res.toString().trim();
                        return rawData;
                    }

                } catch (IOException localIOException) {
                    connectionLost();
                } catch (Exception e) {
                    e.printStackTrace();
                    connectionLost();
                }
                return "";
            }
        }

        public void cancel() {
            mRunningStatus = false;
            try {
                mmSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "close() of connect " + mSocket + " socket failed", e);
            }

            interrupt();

            try {
                join();
            } catch (InterruptedException e) {

            }
        }
    }
}
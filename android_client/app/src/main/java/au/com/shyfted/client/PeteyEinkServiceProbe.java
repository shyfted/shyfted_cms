package au.com.shyfted.client;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import com.geniatech.el133sdk.EpdManager;

final class PeteyEinkServiceProbe {
    private static final String SERVICE_PACKAGE = "com.geniatech.epc.core";
    private static final String SERVICE_CLASS = "com.geniatech.el133sdk.epdService";
    private static final String SERVICE_ACTION = "geniatech.intent.action.epdService";
    private static final String EPD_DESCRIPTOR = "com.geniatech.el133sdk.EpdManager";
    private static final int TRANSACTION_CLSCR = 42;
    private static final int TRANSACTION_SET_DISPLAY_MODE = 43;
    static final int SEND_IMAGE_PENDING = Integer.MIN_VALUE;
    static final int SEND_IMAGE_EXCEPTION = Integer.MIN_VALUE + 1;

    private final Context context;
    private boolean bound;
    private EpdManager epdManager;
    private IBinder serviceBinder;
    private String pendingProbeCall;
    private String pendingProbePath;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.i(ShyftedDeviceClient.TAG, "Petey e-ink service connected component=" + name.flattenToShortString());
            EpdManager epdManager = EpdManager.Stub.asInterface(service);
            if (epdManager == null) {
                Log.e(ShyftedDeviceClient.TAG, "Petey e-ink getServiceVersion failure: EpdManager interface unavailable");
                return;
            }

            PeteyEinkServiceProbe.this.epdManager = epdManager;
            PeteyEinkServiceProbe.this.serviceBinder = service;
            try {
                String version = epdManager.getServiceVersion();
                Log.i(ShyftedDeviceClient.TAG, "Petey e-ink getServiceVersion success version=" + version);
            } catch (RemoteException | RuntimeException e) {
                Log.e(ShyftedDeviceClient.TAG, "Petey e-ink getServiceVersion failure", e);
            }

            if (pendingProbeCall != null) {
                String call = pendingProbeCall;
                String path = pendingProbePath;
                pendingProbeCall = null;
                pendingProbePath = null;
                runVendorProbeCall(call, path);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            epdManager = null;
            serviceBinder = null;
            Log.w(ShyftedDeviceClient.TAG, "Petey e-ink service disconnected component=" + name.flattenToShortString());
        }

        @Override
        public void onBindingDied(ComponentName name) {
            bound = false;
            epdManager = null;
            serviceBinder = null;
            Log.w(ShyftedDeviceClient.TAG, "Petey e-ink service binding died component=" + name.flattenToShortString());
        }

        @Override
        public void onNullBinding(ComponentName name) {
            bound = false;
            epdManager = null;
            serviceBinder = null;
            Log.e(ShyftedDeviceClient.TAG, "Petey e-ink service null binding component=" + name.flattenToShortString());
        }
    };

    PeteyEinkServiceProbe(Context context) {
        this.context = context.getApplicationContext();
    }

    void start() {
        if (bound) {
            return;
        }

        Intent intent = new Intent(SERVICE_ACTION)
                .setComponent(new ComponentName(SERVICE_PACKAGE, SERVICE_CLASS))
                .setPackage(SERVICE_PACKAGE);
        try {
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            Log.i(ShyftedDeviceClient.TAG, "Petey e-ink bind requested result=" + bound
                    + " action=" + SERVICE_ACTION
                    + " component=" + SERVICE_PACKAGE + "/" + SERVICE_CLASS);
        } catch (RuntimeException e) {
            bound = false;
            Log.e(ShyftedDeviceClient.TAG, "Petey e-ink bind failure", e);
        }
    }

    void stop() {
        unbind();
    }

    int sendImage(String imagePath) {
        if (epdManager == null) {
            Log.w(ShyftedDeviceClient.TAG, "Petey e-ink sendImage pending: service not connected; caller must retry image_path=" + imagePath);
            start();
            Log.w(ShyftedDeviceClient.TAG, "Petey e-ink sendImage return_code=" + SEND_IMAGE_PENDING
                    + " image_path=" + imagePath);
            return SEND_IMAGE_PENDING;
        }

        try {
            java.io.File imageFile = new java.io.File(imagePath);
            Log.i(ShyftedDeviceClient.TAG, "Petey e-ink sendImage API call"
                    + " api=EpdManager.sendImage(String path)"
                    + " package=" + SERVICE_PACKAGE
                    + " service=" + SERVICE_CLASS
                    + " image_path=" + imagePath
                    + " exists=" + imageFile.isFile()
                    + " size=" + imageFile.length()
                    + " readable=" + imageFile.canRead());
            int returnCode = epdManager.sendImage(imagePath);
            Log.i(ShyftedDeviceClient.TAG, "Petey e-ink sendImage return_code=" + returnCode
                    + " image_path=" + imagePath
                    + " stdout=unavailable(Binder API)"
                    + " stderr=unavailable(Binder API)");
            if (returnCode == 0) {
                Log.i(ShyftedDeviceClient.TAG, "Petey e-ink sendImage success image_path=" + imagePath
                        + " return_code=" + returnCode);
            } else {
                Log.e(ShyftedDeviceClient.TAG, "Petey e-ink sendImage failure image_path=" + imagePath
                        + " return_code=" + returnCode);
            }
            return returnCode;
        } catch (RemoteException | RuntimeException e) {
            Log.e(ShyftedDeviceClient.TAG, "Petey e-ink sendImage failure image_path=" + imagePath, e);
            Log.e(ShyftedDeviceClient.TAG, "Petey e-ink sendImage return_code=" + SEND_IMAGE_EXCEPTION
                    + " image_path=" + imagePath);
            return SEND_IMAGE_EXCEPTION;
        }
    }

    int runVendorProbeCall(String call, String imagePath) {
        if (epdManager == null || serviceBinder == null) {
            pendingProbeCall = call;
            pendingProbePath = imagePath;
            Log.w(ShyftedDeviceClient.TAG, "EPD_VENDOR_PROBE pending service not connected call=" + call
                    + " image_path=" + imagePath);
            start();
            return SEND_IMAGE_PENDING;
        }

        String normalizedCall = call == null ? "" : call.trim();
        try {
            Log.i(ShyftedDeviceClient.TAG, "EPD_VENDOR_PROBE begin call=" + normalizedCall
                    + " image_path=" + imagePath
                    + " file=" + fileSummary(imagePath));
            int returnCode;
            if ("sendImage".equals(normalizedCall)) {
                returnCode = epdManager.sendImage(imagePath);
                Log.i(ShyftedDeviceClient.TAG, "EPD_VENDOR_PROBE result call=sendImage"
                        + " api=EpdManager.sendImage(String)"
                        + " transaction=2"
                        + " return_code=" + returnCode);
                return returnCode;
            }
            if ("sendImageByNum0".equals(normalizedCall)) {
                returnCode = epdManager.sendImageByNum(imagePath, 0);
                Log.i(ShyftedDeviceClient.TAG, "EPD_VENDOR_PROBE result call=sendImageByNum"
                        + " api=EpdManager.sendImageByNum(String,int)"
                        + " screen_num=0"
                        + " transaction=3"
                        + " return_code=" + returnCode);
                return returnCode;
            }
            if ("sendImageByNum1".equals(normalizedCall)) {
                returnCode = epdManager.sendImageByNum(imagePath, 1);
                Log.i(ShyftedDeviceClient.TAG, "EPD_VENDOR_PROBE result call=sendImageByNum"
                        + " api=EpdManager.sendImageByNum(String,int)"
                        + " screen_num=1"
                        + " transaction=3"
                        + " return_code=" + returnCode);
                return returnCode;
            }
            if ("clScr".equals(normalizedCall)) {
                transactVoid(TRANSACTION_CLSCR);
                Log.i(ShyftedDeviceClient.TAG, "EPD_VENDOR_PROBE result call=clScr"
                        + " api=raw Binder transact"
                        + " transaction=" + TRANSACTION_CLSCR
                        + " return_code=0");
                return 0;
            }
            if ("setDisplayMode0".equals(normalizedCall)) {
                transactVoidWithInt(TRANSACTION_SET_DISPLAY_MODE, 0);
                Log.i(ShyftedDeviceClient.TAG, "EPD_VENDOR_PROBE result call=setDisplayMode"
                        + " api=raw Binder transact"
                        + " mode=0"
                        + " transaction=" + TRANSACTION_SET_DISPLAY_MODE
                        + " return_code=0");
                return 0;
            }
            if ("setDisplayMode1".equals(normalizedCall)) {
                transactVoidWithInt(TRANSACTION_SET_DISPLAY_MODE, 1);
                Log.i(ShyftedDeviceClient.TAG, "EPD_VENDOR_PROBE result call=setDisplayMode"
                        + " api=raw Binder transact"
                        + " mode=1"
                        + " transaction=" + TRANSACTION_SET_DISPLAY_MODE
                        + " return_code=0");
                return 0;
            }

            Log.e(ShyftedDeviceClient.TAG, "EPD_VENDOR_PROBE unknown call=" + normalizedCall);
            return SEND_IMAGE_EXCEPTION;
        } catch (RemoteException | RuntimeException e) {
            Log.e(ShyftedDeviceClient.TAG, "EPD_VENDOR_PROBE failure call=" + normalizedCall
                    + " image_path=" + imagePath, e);
            Log.e(ShyftedDeviceClient.TAG, "EPD_VENDOR_PROBE result call=" + normalizedCall
                    + " return_code=" + SEND_IMAGE_EXCEPTION);
            return SEND_IMAGE_EXCEPTION;
        }
    }

    private void unbind() {
        if (!bound) {
            return;
        }

        try {
            context.unbindService(connection);
        } catch (IllegalArgumentException e) {
            Log.w(ShyftedDeviceClient.TAG, "Petey e-ink unbind skipped", e);
        } finally {
            bound = false;
            epdManager = null;
            serviceBinder = null;
        }
    }

    private void transactVoid(int transactionCode) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(EPD_DESCRIPTOR);
            serviceBinder.transact(transactionCode, data, reply, 0);
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void transactVoidWithInt(int transactionCode, int value) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(EPD_DESCRIPTOR);
            data.writeInt(value);
            serviceBinder.transact(transactionCode, data, reply, 0);
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static String fileSummary(String imagePath) {
        if (imagePath == null) {
            return "null";
        }
        java.io.File imageFile = new java.io.File(imagePath);
        return "exists=" + imageFile.isFile()
                + " size=" + imageFile.length()
                + " readable=" + imageFile.canRead();
    }
}

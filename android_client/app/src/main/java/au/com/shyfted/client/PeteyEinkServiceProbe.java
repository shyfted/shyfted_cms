package au.com.shyfted.client;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.geniatech.el133sdk.EpdManager;

final class PeteyEinkServiceProbe {
    private static final String SERVICE_PACKAGE = "com.geniatech.epc.core";
    private static final String SERVICE_CLASS = "com.geniatech.el133sdk.epdService";
    private static final String SERVICE_ACTION = "geniatech.intent.action.epdService";
    static final int SEND_IMAGE_PENDING = Integer.MIN_VALUE;
    static final int SEND_IMAGE_EXCEPTION = Integer.MIN_VALUE + 1;

    private final Context context;
    private boolean bound;
    private EpdManager epdManager;
    private String pendingImagePath;

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
            try {
                String version = epdManager.getServiceVersion();
                Log.i(ShyftedDeviceClient.TAG, "Petey e-ink getServiceVersion success version=" + version);
            } catch (RemoteException | RuntimeException e) {
                Log.e(ShyftedDeviceClient.TAG, "Petey e-ink getServiceVersion failure", e);
            }

            if (pendingImagePath != null) {
                String imagePath = pendingImagePath;
                pendingImagePath = null;
                sendImage(imagePath);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            epdManager = null;
            Log.w(ShyftedDeviceClient.TAG, "Petey e-ink service disconnected component=" + name.flattenToShortString());
        }

        @Override
        public void onBindingDied(ComponentName name) {
            bound = false;
            epdManager = null;
            Log.w(ShyftedDeviceClient.TAG, "Petey e-ink service binding died component=" + name.flattenToShortString());
        }

        @Override
        public void onNullBinding(ComponentName name) {
            bound = false;
            epdManager = null;
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
            pendingImagePath = imagePath;
            Log.w(ShyftedDeviceClient.TAG, "Petey e-ink sendImage pending: service not connected image_path=" + imagePath);
            start();
            Log.w(ShyftedDeviceClient.TAG, "Petey e-ink sendImage return_code=" + SEND_IMAGE_PENDING
                    + " image_path=" + imagePath);
            return SEND_IMAGE_PENDING;
        }

        try {
            int returnCode = epdManager.sendImage(imagePath);
            Log.i(ShyftedDeviceClient.TAG, "Petey e-ink sendImage return_code=" + returnCode
                    + " image_path=" + imagePath);
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
        }
    }
}

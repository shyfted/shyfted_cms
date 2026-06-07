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

    private final Context context;
    private boolean bound;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.i(ShyftedDeviceClient.TAG, "Petey e-ink service connected component=" + name.flattenToShortString());
            EpdManager epdManager = EpdManager.Stub.asInterface(service);
            if (epdManager == null) {
                Log.e(ShyftedDeviceClient.TAG, "Petey e-ink getServiceVersion failure: EpdManager interface unavailable");
                unbind();
                return;
            }

            try {
                String version = epdManager.getServiceVersion();
                Log.i(ShyftedDeviceClient.TAG, "Petey e-ink getServiceVersion success version=" + version);
            } catch (RemoteException | RuntimeException e) {
                Log.e(ShyftedDeviceClient.TAG, "Petey e-ink getServiceVersion failure", e);
            } finally {
                unbind();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            Log.w(ShyftedDeviceClient.TAG, "Petey e-ink service disconnected component=" + name.flattenToShortString());
        }

        @Override
        public void onBindingDied(ComponentName name) {
            bound = false;
            Log.w(ShyftedDeviceClient.TAG, "Petey e-ink service binding died component=" + name.flattenToShortString());
        }

        @Override
        public void onNullBinding(ComponentName name) {
            bound = false;
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
        }
    }
}

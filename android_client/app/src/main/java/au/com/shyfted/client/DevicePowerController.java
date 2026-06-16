package au.com.shyfted.client;

import android.content.Context;
import android.os.PowerManager;
import android.util.Log;

import java.io.IOException;

final class DevicePowerController {
    private DevicePowerController() {
    }

    static Result restart(Context context) {
        if (runSuCommand("reboot")) {
            return Result.started("Restart command sent via su.");
        }

        try {
            PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (powerManager != null) {
                powerManager.reboot(null);
                return Result.started("Restart command sent via PowerManager.");
            }
        } catch (RuntimeException e) {
            Log.w(ShyftedDeviceClient.TAG, "PowerManager reboot unavailable", e);
        }

        return Result.unavailable();
    }

    static Result powerOff(Context context) {
        if (runSuCommand("reboot -p")) {
            return Result.started("Power-off command sent via su.");
        }

        return Result.unavailable();
    }

    static boolean isSuAvailable() {
        try {
            Process process = new ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start();
            return process.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Log.i(ShyftedDeviceClient.TAG, "su unavailable", e);
            return false;
        }
    }

    private static boolean runSuCommand(String command) {
        try {
            Process process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                Log.i(ShyftedDeviceClient.TAG, "Power command accepted via su command=" + command);
                return true;
            }
            Log.w(ShyftedDeviceClient.TAG, "Power command failed via su command=" + command + " exit_code=" + exitCode);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Log.w(ShyftedDeviceClient.TAG, "Power command unavailable via su command=" + command, e);
        }
        return false;
    }

    static final class Result {
        final boolean started;
        final String message;

        private Result(boolean started, String message) {
            this.started = started;
            this.message = message;
        }

        static Result started(String message) {
            return new Result(true, message);
        }

        static Result unavailable() {
            return new Result(false, "Power control unavailable on this build without root/system permission.");
        }
    }
}

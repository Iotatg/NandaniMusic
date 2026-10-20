package music.nandani.tg;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Native;

/**
 * Minimal JNA binding to TDLib's JSON interface (libtdjson).
 * Uses the client-id API (td_create_client_id / td_send / td_receive).
 */
public interface TdJson extends Library {

    interface LogCallback extends Callback {
        void invoke(int verbosityLevel, String message);
    }

    int td_create_client_id();

    void td_send(int clientId, String request);

    String td_receive(double timeout);

    String td_execute(String request);

    void td_set_log_message_callback(int maxVerbosityLevel, LogCallback callback);

    static TdJson load(String libName) {
        return Native.load(libName, TdJson.class);
    }
}

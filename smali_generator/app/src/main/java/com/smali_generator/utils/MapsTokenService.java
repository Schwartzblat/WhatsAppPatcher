package com.smali_generator.utils;

import android.os.Binder;
import android.os.Bundle;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

/**
 * Answers the Google Maps SDK's IApiTokenService in-process, fetching the token
 * with the original certificate instead of the re-signed one Play services sees.
 */
public class MapsTokenService extends Binder {
    public static final String DESCRIPTOR = "com.google.android.gms.maps.auth.IApiTokenService";

    private static final String TAG = "PATCH";
    private static final int TRANSACTION_GET_TOKEN = 1;

    private final String ownPackage;

    public MapsTokenService(String ownPackage) {
        this.ownPackage = ownPackage;
        // Answers the descriptor while queryLocalInterface still returns null,
        // so the Maps module goes through the Parcel path it was tested with.
        attachInterface(null, DESCRIPTOR);
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code != TRANSACTION_GET_TOKEN) {
            return super.onTransact(code, data, reply, flags);
        }
        data.enforceInterface(DESCRIPTOR);
        Bundle out;
        try {
            out = handle(data);
        } catch (Throwable t) {
            // In-process, nothing turns a throw into a Parcel exception for the caller.
            Log.e(TAG, "Maps: token service failed: " + t);
            out = new Bundle();
            out.putShort("ERROR_CODE", (short) 5);
        }
        reply.writeNoException();
        reply.writeInt(1);
        out.writeToParcel(reply, 0);
        return true;
    }

    private Bundle handle(Parcel data) {
        final Bundle request = data.readInt() != 0 ? Bundle.CREATOR.createFromParcel(data) : null;
        final String requestedPackage = request == null ? null : request.getString("PACKAGE_NAME");
        final String apiKey = request == null ? null : request.getString("API_KEY");
        final int clientType = request == null ? 0 : request.getInt("CLIENT_TYPE", 0);

        short errorCode = MapsTokenProtocol.check(requestedPackage, ownPackage, apiKey);
        String token = null;
        final long now = System.currentTimeMillis();
        if (errorCode == -1) {
            MapsTokenClient.Result result;
            if (Looper.myLooper() == Looper.getMainLooper()) {
                result = OffMainThread.call(() -> MapsTokenClient.fetch(MapsTokenProtocol.BASE_URL,
                                apiKey, ownPackage, AppCert.original(), clientType, now),
                        25000, new MapsTokenClient.Result(null, (short) 5));
            } else {
                result = MapsTokenClient.fetch(MapsTokenProtocol.BASE_URL, apiKey, ownPackage,
                        AppCert.original(), clientType, now);
            }
            token = result.token;
            errorCode = result.errorCode;
        }

        Bundle out = new Bundle();
        if (token != null) {
            out.putString("API_TOKEN", token);
            out.putLong("EXPIRY_TIME", now + MapsTokenProtocol.VALIDITY_MILLIS);
            out.putLong("VALIDITY_DURATION", MapsTokenProtocol.VALIDITY_MILLIS);
            Log.i(TAG, "Maps: token received (" + token.length() + " chars)");
        } else {
            out.putShort("ERROR_CODE", errorCode);
            Log.i(TAG, "Maps: token request refused, ERROR_CODE " + errorCode);
        }
        return out;
    }
}

package com.fsck.k9.crypto;


import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Map;

import android.content.Intent;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;

import com.fsck.k9.mail.internet.MimeMessage;
import com.fsck.k9.mail.internet.MimeUtility;
import okio.ByteString;
import org.openintents.openpgp.AutocryptPeerUpdate;
import org.openintents.openpgp.util.OpenPgpApi;
import org.openintents.openpgp.util.OpenPgpApi.IOpenPgpCallback;
import timber.log.Timber;


public class AutocryptOperations {
    private static final String AUTOCRYPT_HEADER = "Autocrypt";

    private static final String AUTOCRYPT_PARAM_TO = "addr";
    private static final String AUTOCRYPT_PARAM_KEY_DATA = "key";

    private static final String AUTOCRYPT_PARAM_TYPE = "type";
    private static final String AUTOCRYPT_TYPE_1 = "1";

    private static final String AUTOCRYPT_PARAM_PREFER_ENCRYPT = "prefer-encrypt";
    private static final String AUTOCRYPT_PREFER_ENCRYPT_MUTUAL = "mutual";


    public AutocryptOperations() {
    }


    public boolean addAutocryptPeerUpdateToIntentIfPresent(MimeMessage currentMessage, Intent intent) {
        AutocryptHeader autocryptHeader = getValidAutocryptHeader(currentMessage);
        if (autocryptHeader == null) {
            return false;
        }

        String messageFromAddress = currentMessage.getFrom()[0].getAddress();
        if (!autocryptHeader.addr.equalsIgnoreCase(messageFromAddress)) {
            return false;
        }

        Date messageDate = currentMessage.getSentDate();
        Date internalDate = currentMessage.getInternalDate();
        Date effectiveDate = messageDate.before(internalDate) ? messageDate : internalDate;

        AutocryptPeerUpdate data = AutocryptPeerUpdate.createAutocryptPeerUpdate(
                autocryptHeader.keyData, effectiveDate, autocryptHeader.isPreferEncryptMutual);
        intent.putExtra(OpenPgpApi.EXTRA_AUTOCRYPT_PEER_ID, messageFromAddress);
        intent.putExtra(OpenPgpApi.EXTRA_AUTOCRYPT_PEER_UPDATE, data);
        return true;
    }

    public void processCleartextMessageAsync(OpenPgpApi openPgpApi, MimeMessage currentMessage) {
        Intent intent = new Intent(OpenPgpApi.ACTION_UPDATE_AUTOCRYPT_PEER);
        boolean hasInlineKeyData = addAutocryptPeerUpdateToIntentIfPresent(currentMessage, intent);
        if (hasInlineKeyData) {
            openPgpApi.executeApiAsync(intent, null, null, new IOpenPgpCallback() {
                @Override
                public void onReturn(Intent result) {
                    Timber.d("Autocrypt update OK!");
                }
            });
        }
    }

    @Nullable
    @VisibleForTesting
    AutocryptHeader getValidAutocryptHeader(MimeMessage currentMessage) {
        String[] headers = currentMessage.getHeader(AUTOCRYPT_HEADER);
        ArrayList<AutocryptHeader> autocryptHeaders = parseAllAutocryptHeaders(headers);

        boolean isSingleValidHeader = autocryptHeaders.size() == 1;
        return isSingleValidHeader ? autocryptHeaders.get(0) : null;
    }

    @NonNull
    private ArrayList<AutocryptHeader> parseAllAutocryptHeaders(String[] headers) {
        ArrayList<AutocryptHeader> autocryptHeaders = new ArrayList<>();
        for (String header : headers) {
            AutocryptHeader autocryptHeader = parseAutocryptHeader(header);
            if (autocryptHeader != null) {
                autocryptHeaders.add(autocryptHeader);
            }
        }
        return autocryptHeaders;
    }

    @Nullable
    private AutocryptHeader parseAutocryptHeader(String headerValue) {
        Map<String,String> parameters = MimeUtility.getAllHeaderParameters(headerValue);

        String type = parameters.remove(AUTOCRYPT_PARAM_TYPE);
        if (type != null && !type.equals(AUTOCRYPT_TYPE_1)) {
            Timber.e("autocrypt: unsupported type parameter %s", type);
            return null;
        }

        String base64KeyData = parameters.remove(AUTOCRYPT_PARAM_KEY_DATA);
        if (base64KeyData == null) {
            Timber.e("autocrypt: missing key parameter");
            return null;
        }

        ByteString byteString = ByteString.decodeBase64(base64KeyData);
        if (byteString == null) {
            Timber.e("autocrypt: error parsing base64 data");
            return null;
        }

        String to = parameters.remove(AUTOCRYPT_PARAM_TO);
        if (to == null) {
            Timber.e("autocrypt: no to header!");
            return null;
        }

        boolean isPreferEncryptMutual = false;
        String preferEncrypt = parameters.remove(AUTOCRYPT_PARAM_PREFER_ENCRYPT);
        if (AUTOCRYPT_PREFER_ENCRYPT_MUTUAL.equalsIgnoreCase(preferEncrypt)) {
            isPreferEncryptMutual = true;
        }

        if (hasCriticalParameters(parameters)) {
            return null;
        }

        return new AutocryptHeader(parameters, to, byteString.toByteArray(), isPreferEncryptMutual);
    }

    private boolean hasCriticalParameters(Map<String, String> parameters) {
        for (String parameterName : parameters.keySet()) {
            if (parameterName != null && !parameterName.startsWith("_")) {
                return true;
            }
        }
        return false;
    }

    public boolean hasAutocryptHeader(MimeMessage currentMessage) {
        return currentMessage.getHeader(AUTOCRYPT_HEADER).length > 0;
    }

    public void addAutocryptHeaderToMessage(MimeMessage message, byte[] keyData,
            String autocryptAddress, boolean preferEncryptMutual) {
        AutocryptHeader autocryptHeader = new AutocryptHeader(
                Collections.<String,String>emptyMap(), autocryptAddress, keyData, preferEncryptMutual);
        String rawAutocryptHeader = autocryptHeaderToString(autocryptHeader);

        message.addRawHeader(AUTOCRYPT_HEADER, rawAutocryptHeader);
    }

    private String autocryptHeaderToString(AutocryptHeader autocryptHeader) {
        if (!autocryptHeader.parameters.isEmpty()) {
            throw new UnsupportedOperationException("arbitrary parameters not supported");
        }

        String autocryptHeaderString = AUTOCRYPT_HEADER + ": ";
        autocryptHeaderString += AUTOCRYPT_PARAM_TO + "=" + autocryptHeader.addr + ";";
        if (autocryptHeader.isPreferEncryptMutual) {
            autocryptHeaderString += AUTOCRYPT_PARAM_PREFER_ENCRYPT + "=" + AUTOCRYPT_PREFER_ENCRYPT_MUTUAL + ";";
        }
        autocryptHeaderString += AUTOCRYPT_PARAM_KEY_DATA + "=" +
                ByteString.of(autocryptHeader.keyData).base64();
        StringBuilder headerLines = new StringBuilder();
        for (int i = 0, j = autocryptHeaderString.length(); i < j; i += 76) {
            if (i +76 > j) {
                headerLines.append(autocryptHeaderString.substring(i)).append("\n ");
            } else {
                headerLines.append(autocryptHeaderString.substring(i, i+76)).append("\n ");
            }
        }

        return headerLines.toString();
    }

    @VisibleForTesting
    class AutocryptHeader {
        final byte[] keyData;
        final String addr;
        final Map<String,String> parameters;
        final boolean isPreferEncryptMutual;

        private AutocryptHeader(Map<String, String> parameters, String addr, byte[] keyData, boolean isPreferEncryptMutual) {
            this.parameters = parameters;
            this.addr = addr;
            this.keyData = keyData;
            this.isPreferEncryptMutual = isPreferEncryptMutual;
        }
    }
}

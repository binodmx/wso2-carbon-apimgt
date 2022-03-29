package org.wso2.carbon.apimgt.impl.certificatemgt;

import org.apache.commons.io.IOUtils;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;

public final class TrustStoreUtils {
    public static synchronized void loadCerts(KeyStore trustStore, String keyStorePath, char[] password )
            throws CertificateException, NoSuchAlgorithmException, IOException {
        FileInputStream localTrustStoreStream = new FileInputStream(keyStorePath);
        InputStream dest = IOUtils.toBufferedInputStream(localTrustStoreStream);
        localTrustStoreStream.close();
        trustStore.load(dest, password);
        dest.close();
    }
}

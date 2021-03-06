package info.guardianproject.otr;

import info.guardianproject.bouncycastle.util.encoders.Hex;
import info.guardianproject.otr.app.im.R;
import info.guardianproject.otr.app.im.app.ImApp;
import info.guardianproject.otr.app.im.engine.Address;
import info.guardianproject.util.LogCleaner;
import info.guardianproject.util.Version;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.DSAParams;
import java.security.interfaces.DSAPrivateKey;
import java.security.spec.DSAPublicKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.Vector;

import net.java.otr4j.OtrKeyManager;
import net.java.otr4j.OtrKeyManagerListener;
import net.java.otr4j.OtrKeyManagerStore;
import net.java.otr4j.crypto.OtrCryptoEngineImpl;
import net.java.otr4j.crypto.OtrCryptoException;
import net.java.otr4j.session.SessionID;

import org.jivesoftware.smack.util.Base64;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.os.FileObserver;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

import com.google.common.collect.Sets;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

public class OtrAndroidKeyManagerImpl implements OtrKeyManager {

    private static final boolean REGENERATE_LOCAL_PUBLIC_KEY = false;

    private SimplePropertiesStore store;

    private OtrCryptoEngineImpl cryptoEngine;

    private final static String KEY_ALG = "DSA";
    private final static int KEY_SIZE = 1024;
    private final static Version CURRENT_VERSION = new Version("2.0.0");

    private static OtrAndroidKeyManagerImpl _instance;

    private static final String FILE_KEYSTORE_ENCRYPTED = "otr_keystore.ofc";
    private static final String FILE_KEYSTORE_UNENCRYPTED = "otr_keystore";
    
    
    private final static String STORE_ALGORITHM = "PBEWITHMD5AND256BITAES-CBC-OPENSSL";
    
    private static String mKeyStorePassword = null;
    
    public static void setKeyStorePassword (String keyStorePassword)
    {
        mKeyStorePassword = keyStorePassword;
    }
    
    public static synchronized OtrAndroidKeyManagerImpl getInstance(Context context)
            throws IOException {
        
        if (_instance == null && mKeyStorePassword != null) {
            File f = new File(context.getApplicationContext().getFilesDir(), FILE_KEYSTORE_ENCRYPTED);
            _instance = new OtrAndroidKeyManagerImpl(f,mKeyStorePassword);
        }

        return _instance;
    }

    private OtrAndroidKeyManagerImpl(File filepath, String password) throws IOException {
        this.store = new SimplePropertiesStore(filepath, password, false);
        upgradeStore();

        cryptoEngine = new OtrCryptoEngineImpl();
        
        FileObserver observer = new FileObserver(filepath.getCanonicalPath()) { // set up a file observer to watch this keystore
            @Override
            public void onEvent(int event, String file) {
                
                try
                {
                    OtrDebugLogger.log("reloading key store from disk b/c it changed");
                    store.reload();
                }
                catch (Exception e){} 
                
            }
        };
        observer.startWatching(); // start the observer
    
    }
    
    private void upgradeStore() {
        String version = store.getPropertyString("version");

        if (version == null || new Version(version).compareTo(new Version("1.0.0")) < 0) {
            // Add verified=false entries for TOFU sync purposes
            Set<Object> keys = Sets.newHashSet(store.getKeySet()); 
            for (Object keyObject : keys) {
                String key = (String)keyObject;
                if (key.endsWith(".fingerprint")) {
                    String fullUserId = key.replaceAll(".fingerprint$", "");
                    String fingerprint = store.getPropertyString(key);
                    String verifiedKey = buildPublicKeyVerifiedId(fullUserId, fingerprint);
                    if (!store.hasProperty(verifiedKey)) {
                        // Avoid save
                        store.setProperty(verifiedKey, "false");
                    }
                }
            }
            
            File fileOldKeystore = new File(FILE_KEYSTORE_UNENCRYPTED);
            if (fileOldKeystore.exists())
            {
                try {
                    SimplePropertiesStore storeOldKeystore = new SimplePropertiesStore(fileOldKeystore);
                    
                    Enumeration<Object> enumKeys = storeOldKeystore.getKeys();
                    
                    while(enumKeys.hasMoreElements())
                    {
                        String key = (String)enumKeys.nextElement();
                        store.setProperty(key, storeOldKeystore.getPropertyString(key));
                        
                    }
                    
                    store.save();
                    
                    fileOldKeystore.delete();
                    
                } catch (IOException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            }
            
            // This will save
            store.setProperty("version", CURRENT_VERSION.toString());
        }
    }

    static class SimplePropertiesStore implements OtrKeyManagerStore {
        
        private Properties mProperties = new Properties();
        private File mStoreFile;
        private String mPassword;

        
        public SimplePropertiesStore(File storeFile) throws IOException {
            mStoreFile = storeFile;
            mProperties.clear();

            mProperties.load(new FileInputStream(mStoreFile));
            
        }

        public SimplePropertiesStore(File storeFile, final String password, boolean isImportFromKeySync) throws IOException {
            
            OtrDebugLogger.log("Loading store from encrypted file");
            mStoreFile = storeFile;
            mProperties.clear();
            
            if (password == null)
                throw new IOException ("invalid password");
             
            mPassword = password;

            if (isImportFromKeySync)
                loadAES(password);
            else
                loadOpenSSL(password);
        }

        public void reload () throws IOException
        {
            loadOpenSSL(mPassword);
        }
        
        private void loadAES(final String password) throws IOException 
        {
            String decoded;
                decoded = AES_256_CBC.decrypt(mStoreFile, password);
                mProperties.load(new ByteArrayInputStream(decoded.getBytes()));
        }

        public void setProperty(String id, String value) {
            mProperties.setProperty(id, value);
            
            save();
        }
        

        public void setProperty(String id, boolean value) {
            mProperties.setProperty(id, Boolean.toString(value));
            

            save();
        }

        
        public boolean save ()
        {
            try {
                saveOpenSSL (mPassword, mStoreFile);
                return true;
            } catch (IOException e) {
                LogCleaner.error(ImApp.LOG_TAG, "error saving keystore", e);
                return false;
            }
        }
        
        public boolean export (String password, File storeFile)
        {
            try {
                saveOpenSSL (password, storeFile);
                return true;
            } catch (IOException e) {
                LogCleaner.error(ImApp.LOG_TAG, "error saving keystore", e);
                return false;
            }
        }
        
        private void saveOpenSSL (String password, File fileStore) throws IOException
        {
            // Encrypt these bytes
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            OpenSSLPBEOutputStream encOS = new OpenSSLPBEOutputStream(baos, STORE_ALGORITHM, 1, password.toCharArray());
            mProperties.store(encOS, null);
            encOS.flush();
            
            FileOutputStream fos = new FileOutputStream(fileStore);
            fos.write(baos.toByteArray());
            fos.flush();
            fos.close();

        }
        
        private void loadOpenSSL(String password) throws IOException
        {
            
            if (!mStoreFile.exists())
                return;
            
            if (mStoreFile.length() == 0)
                return;
            
            FileInputStream fis = null;
            
            try {

                fis = new FileInputStream(mStoreFile);

                
                // Decrypt the bytes
                OpenSSLPBEInputStream encIS = new OpenSSLPBEInputStream(fis, STORE_ALGORITHM, 1, password.toCharArray());
                
                    mProperties.load(encIS);
                
            } catch (FileNotFoundException fnfe) {
                OtrDebugLogger.log("Properties store file not found: First time?");
                mStoreFile.getParentFile().mkdirs();


            } 
        }
        
        public void setProperty(String id, byte[] value) {
            mProperties.setProperty(id, new String(Base64.encodeBytes(value)));

            save();
        }

        // Store as hex bytes
        public void setPropertyHex(String id, byte[] value) {
            mProperties.setProperty(id, new String(Hex.encode(value)));


            save();
        }

        public void removeProperty(String id) {
            mProperties.remove(id);

        }

        public String getPropertyString(String id) {
            return mProperties.getProperty(id);
        }
        
        public byte[] getPropertyBytes(String id) {
            String value = mProperties.getProperty(id);

            if (value != null)
                return Base64.decode(value);
            return null;
        }

        // Load from hex bytes
        public byte[] getPropertyHexBytes(String id) {
            String value = mProperties.getProperty(id);

            if (value != null)
                return Hex.decode(value);
            return null;
        }

        public boolean getPropertyBoolean(String id, boolean defaultValue) {
            try {
                return Boolean.valueOf(mProperties.get(id).toString());
            } catch (Exception e) {
                return defaultValue;
            }
        }

        public boolean hasProperty(String id) {
            return mProperties.containsKey(id);
        }
        
        public Enumeration<Object> getKeys ()
        {
            return mProperties.keys();
        }
        
        public Set<Object> getKeySet ()
        {
            return mProperties.keySet();
        }
    }

    private List<OtrKeyManagerListener> listeners = new Vector<OtrKeyManagerListener>();

    public void addListener(OtrKeyManagerListener l) {
        synchronized (listeners) {
            if (!listeners.contains(l))
                listeners.add(l);
        }
    }

    public void removeListener(OtrKeyManagerListener l) {
        synchronized (listeners) {
            listeners.remove(l);
        }
    }

    public void generateLocalKeyPair(SessionID sessionID) {
        if (sessionID == null)
            return;

        String accountID = sessionID.getAccountID();

        generateLocalKeyPair(accountID);
    }

    public void regenerateLocalPublicKey(KeyFactory factory, String accountID, DSAPrivateKey privKey) {
        BigInteger x = privKey.getX();
        DSAParams params = privKey.getParams();
        BigInteger y = params.getG().modPow(x, params.getP());
        DSAPublicKeySpec keySpec = new DSAPublicKeySpec(y, params.getP(), params.getQ(), params.getG());
        PublicKey pubKey;
        try {
            pubKey = factory.generatePublic(keySpec);
        } catch (InvalidKeySpecException e) {
            throw new RuntimeException(e);
        }
        storeLocalPublicKey(accountID, pubKey);
    }
    
    public void generateLocalKeyPair(String accountID) {

        OtrDebugLogger.log("generating local key pair for: " + accountID);

        KeyPair keyPair;
        try {

            KeyPairGenerator kpg = KeyPairGenerator.getInstance(KEY_ALG);
            kpg.initialize(KEY_SIZE);

            keyPair = kpg.genKeyPair();
        } catch (NoSuchAlgorithmException e) {
            OtrDebugLogger.log("no such algorithm", e);
            return;
        }

        OtrDebugLogger.log("SUCCESS! generating local key pair for: " + accountID);

        // Store Private Key.
        PrivateKey privKey = keyPair.getPrivate();
        PKCS8EncodedKeySpec pkcs8EncodedKeySpec = new PKCS8EncodedKeySpec(privKey.getEncoded());

        this.store.setProperty(accountID + ".privateKey", pkcs8EncodedKeySpec.getEncoded());

        // Store Public Key.
        PublicKey pubKey = keyPair.getPublic();
        storeLocalPublicKey(accountID, pubKey);
    }

    private void storeLocalPublicKey(String accountID, PublicKey pubKey) {
        X509EncodedKeySpec x509EncodedKeySpec = new X509EncodedKeySpec(pubKey.getEncoded());

        this.store.setProperty(accountID + ".publicKey", x509EncodedKeySpec.getEncoded());

        // Stash fingerprint for consistency.
        try {
            String fingerprintString = new OtrCryptoEngineImpl().getFingerprint(pubKey);
            this.store.setPropertyHex(accountID + ".fingerprint", Hex.decode(fingerprintString));
        } catch (OtrCryptoException e) {
            e.printStackTrace();
        }
    }
    
    public void importKeyStore(File filePath, String password, boolean overWriteExisting, boolean deleteImportedFile) throws IOException
    {
        SimplePropertiesStore storeNew = null;

        if (filePath.getName().endsWith(".ofcaes")) {
            //TODO implement GUI to get password via QR Code, and handle wrong password
            storeNew = new SimplePropertiesStore(filePath, password, true);
            deleteImportedFile = true; // once its imported, its no longer needed
        } 
        
        
        Enumeration<Object> enumKeys = storeNew.getKeys();
        
        
        String key;
        
        while (enumKeys.hasMoreElements())
        {
            key = (String)enumKeys.nextElement();
            
            boolean hasKey = store.hasProperty(key);
            
            if (!hasKey || overWriteExisting)
                store.setProperty(key, storeNew.getPropertyString(key));
            
        }

        if (deleteImportedFile)
            filePath.delete();
    }

    public String getLocalFingerprint(SessionID sessionID) {
        return getLocalFingerprint(sessionID.getAccountID());
    }

    public String getLocalFingerprint(String userId) {
        KeyPair keyPair = loadLocalKeyPair(userId);

        if (keyPair == null)
            return null;

        PublicKey pubKey = keyPair.getPublic();

        try {
            String fingerprint = cryptoEngine.getFingerprint(pubKey);

            OtrDebugLogger.log("got fingerprint for: " + userId + "=" + fingerprint);

            return fingerprint;

        } catch (OtrCryptoException e) {
            e.printStackTrace();
            return null;
        }
    }

    public String getRemoteFingerprint(SessionID sessionID) {
        return getRemoteFingerprint(sessionID.getFullUserID());
    }

    public String getRemoteFingerprint(String userId) {
        if (!Address.hasResource(userId))
            return null;
        byte[] fingerprint = this.store.getPropertyHexBytes(userId + ".fingerprint");
        if (fingerprint != null) {
            // If we have a fingerprint stashed, assume it is correct.
            return new String(Hex.encode(fingerprint, 0, fingerprint.length));
        }
        PublicKey remotePublicKey = loadRemotePublicKeyFromStore(userId);
        if (remotePublicKey == null)
            return null;
        try {
            // Store the fingerprint, for posterity.
            String fingerprintString = new OtrCryptoEngineImpl().getFingerprint(remotePublicKey);
            this.store.setPropertyHex(userId + ".fingerprint", Hex.decode(fingerprintString));
            return fingerprintString;
        } catch (OtrCryptoException e) {
            OtrDebugLogger.log("OtrCryptoException getting remote fingerprint",e);
            return null;
        }
    }

    public boolean isVerified(SessionID sessionID) {
        if (sessionID == null)
            return false;
        
        String userId = sessionID.getUserID();
        String fullUserID = sessionID.getFullUserID();
        
        if (Address.hasResource(userId))
            return false;

        if (!Address.hasResource(fullUserID))
            return false;
        
        String remoteFingerprint =getRemoteFingerprint(fullUserID);
        
        if (remoteFingerprint != null)
        {
            String pubKeyVerifiedToken = buildPublicKeyVerifiedId(userId, remoteFingerprint);
            return this.store.getPropertyBoolean(pubKeyVerifiedToken, false);
        }
        else
        {
            return false;
        }
    }

    public boolean isVerifiedUser(String fullUserId) {
        if (fullUserId == null)
            return false;

        String userId = Address.stripResource(fullUserId);
        String pubKeyVerifiedToken = buildPublicKeyVerifiedId(userId, getRemoteFingerprint(fullUserId));

        return this.store.getPropertyBoolean(pubKeyVerifiedToken, false);
    }

    public KeyPair loadLocalKeyPair(SessionID sessionID) {
        if (sessionID == null)
            return null;

        String accountID = sessionID.getAccountID();
        return loadLocalKeyPair(accountID);
    }

    private KeyPair loadLocalKeyPair(String accountID) {
        PublicKey publicKey;
        PrivateKey privateKey;


        try {
            // Load Private Key.

            byte[] b64PrivKey = this.store.getPropertyBytes(accountID + ".privateKey");
            if (b64PrivKey == null)
                return null;

            PKCS8EncodedKeySpec privateKeySpec = new PKCS8EncodedKeySpec(b64PrivKey);

            // Generate KeyPair.
            KeyFactory keyFactory;
            keyFactory = KeyFactory.getInstance(KEY_ALG);
            privateKey = keyFactory.generatePrivate(privateKeySpec);

            if (REGENERATE_LOCAL_PUBLIC_KEY) {
                regenerateLocalPublicKey(keyFactory, accountID, (DSAPrivateKey)privateKey);
            }
            
            // Load Public Key.
            byte[] b64PubKey = this.store.getPropertyBytes(accountID + ".publicKey");
            if (b64PubKey == null)
                return null;

            X509EncodedKeySpec publicKeySpec = new X509EncodedKeySpec(b64PubKey);
            publicKey = keyFactory.generatePublic(publicKeySpec);
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            return null;
        } catch (InvalidKeySpecException e) {
            e.printStackTrace();
            return null;
        }

        return new KeyPair(publicKey, privateKey);
    }

    public PublicKey loadRemotePublicKey(SessionID sessionID) {

        return loadRemotePublicKeyFromStore(sessionID.getFullUserID());
    }

    private PublicKey loadRemotePublicKeyFromStore(String userId) {
        if (!Address.hasResource(userId))
           return null;
        
        byte[] b64PubKey = this.store.getPropertyBytes(userId + ".publicKey");
        if (b64PubKey == null) {
            return null;

        }

        X509EncodedKeySpec publicKeySpec = new X509EncodedKeySpec(b64PubKey);

        // Generate KeyPair from spec
        KeyFactory keyFactory;
        try {
            keyFactory = KeyFactory.getInstance(KEY_ALG);

            return keyFactory.generatePublic(publicKeySpec);
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            return null;
        } catch (InvalidKeySpecException e) {
            e.printStackTrace();
            return null;
        }
    }

    public void savePublicKey(SessionID sessionID, PublicKey pubKey) {
        if (sessionID == null)
            return;

        X509EncodedKeySpec x509EncodedKeySpec = new X509EncodedKeySpec(pubKey.getEncoded());

        String userId = sessionID.getFullUserID();
        if (!Address.hasResource(userId))
            return;
        
        this.store.setProperty(userId + ".publicKey", x509EncodedKeySpec.getEncoded());
        // Stash the associated fingerprint.  This saves calculating it in the future
        // and is useful for transferring rosters to other apps.
        try {
            String fingerprintString = new OtrCryptoEngineImpl().getFingerprint(pubKey);
            String verifiedToken = buildPublicKeyVerifiedId(userId, fingerprintString.toLowerCase());
            if (!this.store.hasProperty(verifiedToken))
                this.store.setProperty(verifiedToken, false);
            this.store.setPropertyHex(userId + ".fingerprint", Hex.decode(fingerprintString));
        } catch (OtrCryptoException e) {
            e.printStackTrace();
        }
    }

    public void unverify(SessionID sessionID) {
        if (sessionID == null)
            return;

        if (!isVerified(sessionID))
            return;

        String userId = sessionID.getUserID();

        this.store.setProperty(buildPublicKeyVerifiedId(userId, getRemoteFingerprint(sessionID)), false);

        for (OtrKeyManagerListener l : listeners)
            l.verificationStatusChanged(sessionID);

    }

    public void unverifyUser(String userId) {
        if (userId == null)
            return;

        if (!isVerifiedUser(userId))
            return;

        this.store.setProperty(buildPublicKeyVerifiedId(userId, getRemoteFingerprint(userId)), false);

        //	for (OtrKeyManagerListener l : listeners)
        //	l.verificationStatusChanged(sessionID);

    }

    public void verify(SessionID sessionID) {
        if (sessionID == null)
            return;

        if (this.isVerified(sessionID))
            return;

        String userId = sessionID.getUserID();

        this.store
                .setProperty(buildPublicKeyVerifiedId(userId, getRemoteFingerprint(sessionID)), true);

        for (OtrKeyManagerListener l : listeners)
            l.verificationStatusChanged(sessionID);
    }

    public void remoteVerifiedUs(SessionID sessionID) {
        if (sessionID == null)
            return;

        for (OtrKeyManagerListener l : listeners)
            l.remoteVerifiedUs(sessionID);
    }

    private static String buildPublicKeyVerifiedId(String userId, String fingerprint) {
        if (fingerprint == null)
            return null;

        return Address.stripResource(userId) + "." + fingerprint + ".publicKey.verified";
    }

    public void verifyUser(String userId) {
        if (userId == null)
            return;

        if (this.isVerifiedUser(userId))
            return;

        this.store
                .setProperty(buildPublicKeyVerifiedId(userId, getRemoteFingerprint(userId)), true);

        //for (OtrKeyManagerListener l : listeners)
        //l.verificationStatusChanged(userId);

    }

    public boolean doKeyStoreExport (String password)
    {
        

        // if otr_keystore.ofcaes is in the SDCard root, import it
        File otrKeystoreAES = new File(Environment.getExternalStorageDirectory(),
                "otr_keystore.ofcaes");
        
        
        return store.export(password, otrKeystoreAES);
    }
    public static boolean checkForKeyImport (Intent intent, Activity activity)
    {
        boolean doKeyStoreImport = false;
        
        // if otr_keystore.ofcaes is in the SDCard root, import it
        File otrKeystoreAES = new File(Environment.getExternalStorageDirectory(),
                "otr_keystore.ofcaes");
        if (otrKeystoreAES.exists()) {
            //Log.i(TAG, "found " + otrKeystoreAES + "to import");
            doKeyStoreImport = true;
            importOtrKeyStore(otrKeystoreAES, activity);
        }
        else if (intent.getData() != null)
        {
            Uri uriData = intent.getData();
            String path = null;
            
            if(uriData.getScheme() != null && uriData.getScheme().equals("file"))
            {
                path = uriData.toString().replace("file://", "");
            
                File file = new File(path);
                
                doKeyStoreImport = true;
                
                importOtrKeyStore(file, activity);
            }
        }
        
        return doKeyStoreImport;
    }
    
    
    public static void importOtrKeyStore (final File fileOtrKeyStore, final Activity activity)
    {
     
        try
        {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity.getApplicationContext());

            prefs.edit().putString("keystoreimport", fileOtrKeyStore.getCanonicalPath()).commit();
        }
        catch (IOException ioe)
        {
            Log.e("TAG","problem importing key store",ioe);
            return;
        }

        Dialog.OnClickListener ocl = new Dialog.OnClickListener ()
        {

            @Override
            public void onClick(DialogInterface dialog, int which) {
                
                //launch QR code intent
                IntentIntegrator.initiateScan(activity);
                
            }
        };
        

        new AlertDialog.Builder(activity).setTitle(R.string.confirm)
                  .setMessage(R.string.detected_Otr_keystore_import)
                  .setPositiveButton(R.string.yes, ocl) // default button
                  .setNegativeButton(R.string.no, null).setCancelable(true).show();
      
      
    }
    
    public void importOtrKeyStoreWithPassword (final File fileOtrKeyStore, String importPassword, String keyStorePassword, Activity activity) throws IOException
    {

       
        boolean overWriteExisting = true;
        boolean deleteImportedFile = true;
        importKeyStore(fileOtrKeyStore, importPassword, overWriteExisting, deleteImportedFile);
        
    
    }
    
    public boolean handleKeyScanResult (int requestCode, int resultCode, Intent data, Activity activity, String keyStorePassword)
    {
        IntentResult scanResult =
                IntentIntegrator.parseActivityResult(requestCode, resultCode, data); 
        
        if  (scanResult != null) 
        { 
            
            String otrKeyPassword = scanResult.getContents();
            
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity.getApplicationContext());

            String otrKeyStorePath = prefs.getString("keystoreimport", null);
            
            Log.d("OTR","got password: " + otrKeyPassword + " for path: " + otrKeyStorePath);
            
            if (otrKeyPassword != null && otrKeyStorePath != null)
            {
                
                otrKeyPassword = otrKeyPassword.replace("\n","").replace("\r", ""); //remove any padding, newlines, etc
                
                try
                {
                    File otrKeystoreAES = new File(otrKeyStorePath);
                    if (otrKeystoreAES.exists()) {
                        importOtrKeyStoreWithPassword(otrKeystoreAES, otrKeyPassword,  keyStorePassword, activity);
                        return true;
                    }
                }
                catch (IOException e)
                {
                    Toast.makeText(activity, "unable to open keystore for import", Toast.LENGTH_LONG).show();
                    return false;
                }
            }
            else
            {
                Log.d("OTR","no key store path saved");
                return false;
            }
            
        } 
        
        return false;
    }

}

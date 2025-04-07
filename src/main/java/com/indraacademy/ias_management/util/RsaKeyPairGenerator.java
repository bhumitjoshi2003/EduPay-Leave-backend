package com.indraacademy.ias_management.util;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

public class RsaKeyPairGenerator {

    public static void main(String[] args) {
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(2048); // Key size

            KeyPair keyPair = keyPairGenerator.generateKeyPair();

            // Private Key
            byte[] privateKeyBytes = keyPair.getPrivate().getEncoded();
            String privateKeyBase64 = Base64.getEncoder().encodeToString(privateKeyBytes);
            Files.write(Paths.get("privateKey.pem"), privateKeyBytes);

            // Public Key
            byte[] publicKeyBytes = keyPair.getPublic().getEncoded();
            String publicKeyBase64 = Base64.getEncoder().encodeToString(publicKeyBytes);
            Files.write(Paths.get("publicKey.pem"), publicKeyBytes);

            System.out.println("Private Key (Base64):\n" + privateKeyBase64);
            System.out.println("\nPublic Key (Base64):\n" + publicKeyBase64);

            System.out.println("\nPrivate Key saved to privateKey.pem");
            System.out.println("Public Key saved to publicKey.pem");

        } catch (NoSuchAlgorithmException | IOException e) {
            e.printStackTrace();
        }
    }
}
package com.model;

public class TlsConfig {

    private Boolean enabled = false;

    private String certificateFile;

    private String privateKeyFile;

    private String privateKeyPassword;

    private String sniHost;

    private Boolean insecureSkipVerify = false;

    private String trustCertCollectionFile;

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public String getCertificateFile() {
        return certificateFile;
    }

    public void setCertificateFile(String certificateFile) {
        this.certificateFile = certificateFile;
    }

    public String getPrivateKeyFile() {
        return privateKeyFile;
    }

    public void setPrivateKeyFile(String privateKeyFile) {
        this.privateKeyFile = privateKeyFile;
    }

    public String getPrivateKeyPassword() {
        return privateKeyPassword;
    }

    public void setPrivateKeyPassword(String privateKeyPassword) {
        this.privateKeyPassword = privateKeyPassword;
    }

    public String getSniHost() {
        return sniHost;
    }

    public void setSniHost(String sniHost) {
        this.sniHost = sniHost;
    }

    public Boolean getInsecureSkipVerify() {
        return insecureSkipVerify;
    }

    public void setInsecureSkipVerify(Boolean insecureSkipVerify) {
        this.insecureSkipVerify = insecureSkipVerify;
    }

    public String getTrustCertCollectionFile() {
        return trustCertCollectionFile;
    }

    public void setTrustCertCollectionFile(String trustCertCollectionFile) {
        this.trustCertCollectionFile = trustCertCollectionFile;
    }

    public void applyDefaults() {
        if (enabled == null) {
            enabled = false;
        }
        if (insecureSkipVerify == null) {
            insecureSkipVerify = false;
        }
    }
}

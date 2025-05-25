package com.example.core.services.impl;

import com.example.core.config.KalturaConfig;
import com.example.core.services.KalturaConfigManager;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.Map;

@Component(service = KalturaConfigManager.class, immediate = true)
@Designate(ocd = KalturaConfig.class, factory = true) // Factory true to allow multiple configs if needed, though we'll use one.
public class KalturaConfigManagerImpl implements KalturaConfigManager {

    private static final Logger LOG = LoggerFactory.getLogger(KalturaConfigManagerImpl.class);
    private static final String CONFIG_PID = "com.example.core.config.KalturaConfig";

    @Reference
    private ConfigurationAdmin configAdmin;

    private volatile KalturaConfig currentConfig;

    @Activate
    protected void activate(KalturaConfig config) {
        this.currentConfig = config;
        LOG.info("KalturaConfigManagerImpl activated with Client ID: {}", config.kalturaClientId());
    }

    @Override
    public KalturaConfig getConfiguration() {
        try {
            Configuration configuration = configAdmin.getConfiguration(CONFIG_PID);
            if (configuration != null && configuration.getProperties() != null) {
                // This is a bit of a roundabout way to get the typed config
                // In a real scenario, you'd often just use the @Activate injected config
                // if you only need to read. This method allows getting the "live" one.
                // However, the @Designate and @Activate mechanism is usually preferred for typed access.
                // For simplicity, we'll rely on the @Activate-injected one primarily for reads.
                // The updateConfiguration method shows how to modify it.
                return this.currentConfig; // Return the activated config.
            }
        } catch (IOException e) {
            LOG.error("Error retrieving Kaltura configuration", e);
        }
        return null; // Or return a default/empty config
    }

    @Override
    public void updateConfiguration(Map<String, Object> newProperties) throws IOException {
        Configuration configuration = configAdmin.getConfiguration(CONFIG_PID);
        Dictionary<String, Object> properties = configuration.getProperties();
        if (properties == null) {
            properties = new Hashtable<>();
        }
        for (Map.Entry<String, Object> entry : newProperties.entrySet()) {
            properties.put(entry.getKey(), entry.getValue());
        }
        configuration.update(properties);
        LOG.info("Kaltura configuration updated.");
        // The @Activate method should be re-triggered by OSGi when the config is updated.
    }

    @Override
    public String getConfigProperty(String propertyName) {
        KalturaConfig config = getConfiguration();
        if (config != null) {
            // This is not ideal, reflection would be better, or direct access if @Activate config is always current.
            // For this example, we'll use a simple switch.
            switch (propertyName) {
                case "kalturaClientId": return config.kalturaClientId();
                case "kalturaClientSecret": return config.kalturaClientSecret();
                case "kalturaAuthUrl": return config.kalturaAuthUrl();
                case "kalturaTokenUrl": return config.kalturaTokenUrl();
                case "appBuilderOAuthHandlerUrl": return config.appBuilderOAuthHandlerUrl();
                case "kalturaVideoDetailsAppBuilderUrl": return config.kalturaVideoDetailsAppBuilderUrl();
                case "kalturaWebhookApiKey": return config.kalturaWebhookApiKey();
                case "kalturaGenerateThumbnailAppBuilderUrl": return config.kalturaGenerateThumbnailAppBuilderUrl();
                case "kalturaAccessToken": return config.kalturaAccessToken();
                case "kalturaRefreshToken": return config.kalturaRefreshToken();
                default: return null;
            }
        }
        return null;
    }
     @Override
    public long getConfigPropertyAsLong(String propertyName) {
        KalturaConfig config = getConfiguration();
        if (config != null) {
            if ("kalturaTokenExpiryTime".equals(propertyName)) {
                return config.kalturaTokenExpiryTime();
            }
        }
        return 0L;
    }
}

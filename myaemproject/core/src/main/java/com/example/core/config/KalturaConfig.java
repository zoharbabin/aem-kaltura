package com.example.core.config;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.AttributeType;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(name = "Kaltura API Configuration",
                       description = "Configuration for Kaltura API integration including OAuth details.")
public @interface KalturaConfig {

    @AttributeDefinition(name = "Kaltura Client ID",
                         description = "Client ID for the Kaltura application.",
                         type = AttributeType.STRING)
    String kalturaClientId();

    @AttributeDefinition(name = "Kaltura Client Secret",
                         description = "Client Secret for the Kaltura application. This is a sensitive value.",
                         type = AttributeType.PASSWORD)
    String kalturaClientSecret();

    @AttributeDefinition(name = "Kaltura Authorization URL",
                         description = "Full URL to Kaltura's OAuth2 authorization endpoint.",
                         type = AttributeType.STRING,
                         defaultValue = "https://api.kaltura.com/v2/oauth2/authorize")
    String kalturaAuthUrl();

    @AttributeDefinition(name = "Kaltura Token URL",
                         description = "Full URL to Kaltura's OAuth2 token endpoint.",
                         type = AttributeType.STRING,
                         defaultValue = "https://api.kaltura.com/v2/oauth2/token")
    String kalturaTokenUrl();

    @AttributeDefinition(name = "App Builder OAuth Handler URL",
                         description = "Full URL to the App Builder action that handles OAuth steps (kaltura-oauth-handler).",
                         type = AttributeType.STRING)
    String appBuilderOAuthHandlerUrl();

    @AttributeDefinition(name = "Kaltura Access Token",
                         description = "Stored Kaltura Access Token. (Usually not set via config, but via OAuth flow)",
                         type = AttributeType.STRING)
    String kalturaAccessToken();

    @AttributeDefinition(name = "Kaltura Refresh Token",
                         description = "Stored Kaltura Refresh Token. (Usually not set via config, but via OAuth flow)",
                         type = AttributeType.STRING)
    String kalturaRefreshToken();

    @AttributeDefinition(name = "Token Expiry Time",
            description = "Stored token expiry time (timestamp). (Usually not set via config, but via OAuth flow)",
            type = AttributeType.LONG)
    long kalturaTokenExpiryTime();

    @AttributeDefinition(name = "App Builder Video Details Action URL",
            description = "Full URL to the App Builder action that fetches video details (kaltura-video-details).",
            type = AttributeType.STRING)
    String kalturaVideoDetailsAppBuilderUrl();

    @AttributeDefinition(name = "AEM Webhook API Key",
            description = "Shared secret/API Key for validating incoming webhook calls from App Builder to AEM.",
            type = AttributeType.PASSWORD)
    String kalturaWebhookApiKey();

    @AttributeDefinition(name = "App Builder Generate Thumbnail Action URL",
            description = "Full URL to the App Builder action that generates thumbnails (kaltura-generate-thumbnail).",
            type = AttributeType.STRING)
    String kalturaGenerateThumbnailAppBuilderUrl();
}

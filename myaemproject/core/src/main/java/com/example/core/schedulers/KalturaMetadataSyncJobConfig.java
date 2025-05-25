package com.example.core.schedulers;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.AttributeType;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(name = "Kaltura Metadata Synchronization Job Configuration",
                       description = "Configuration for the scheduled job that synchronizes metadata from Kaltura to AEM components.")
public @interface KalturaMetadataSyncJobConfig {

    @AttributeDefinition(name = "Scheduler Expression",
                         description = "Cron-job expression that defines when the job should run. Default is daily at 2 AM.",
                         type = AttributeType.STRING)
    String scheduler_expression() default "0 0 2 * * ?"; // Daily at 2 AM

    @AttributeDefinition(name = "Synchronization Enabled",
                         description = "Enable or disable the metadata synchronization job.",
                         type = AttributeType.BOOLEAN)
    boolean syncEnabled() default true;

    @AttributeDefinition(name = "Search Paths",
                         description = "Paths under which to search for components with Kaltura Entry IDs (e.g., /content/my-site).",
                         type = AttributeType.STRING)
    String[] searchPaths() default {"/content/myaemproject"}; // Default search path

    @AttributeDefinition(name = "Component Resource Types",
                         description = "Sling resource types of the components to be synchronized. Example: myaemproject/components/content/kaltura-video-player",
                         type = AttributeType.STRING)
    String[] componentResourceTypes() default {
        "myaemproject/components/content/kaltura-video-player",
        "myaemproject/components/content/kaltura-video-upload" // If this component also stores entryId and needs sync
    };
}

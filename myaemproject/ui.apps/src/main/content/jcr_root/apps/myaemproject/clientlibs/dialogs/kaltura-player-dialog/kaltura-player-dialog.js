(function(document, $, Coral) {
    "use strict";

    $(document).on("foundation-contentloaded", function() {
        // Target the specific dialog for the kaltura-video-player component
        // The dialog content is usually loaded into a form with class .cq-dialog
        var $dialog = $("form.cq-dialog");
        if ($dialog.length === 0 || $dialog.find(".kaltura-generate-thumbnail-button").length === 0) {
            // Not the kaltura-video-player dialog or button not found
            return;
        }
        
        // More specific targeting if multiple dialogs could be on screen (less likely for page properties)
        // This listener setup assumes the dialog content is fully loaded when foundation-contentloaded triggers.

        var $generateButton = $dialog.find(".kaltura-generate-thumbnail-button");
        var $statusDisplay = $dialog.find(".kaltura-thumbnail-generation-status");
        var $timecodeField = $dialog.find("[name='./thumbnailTimecodeMs']");
        var $entryIdField = $dialog.find("[name='./kalturaEntryId']"); // Assuming this field exists and has the entryId
        var $componentPathField = $dialog.find("[name='./componentPath']"); // Hidden field for component path


        if ($generateButton.length === 0) {
            console.warn("Kaltura Generate Thumbnail button not found in dialog.");
            return;
        }
        
        console.log("Kaltura Player Dialog JS loaded. Button found:", $generateButton.length > 0);


        $generateButton.on("click", function() {
            var timecodeMs = $timecodeField.val();
            var entryId = $entryIdField.val(); // Get entryId from its field
            var componentPath = $componentPathField.val();


            if (!entryId) {
                $statusDisplay.text("Error: Kaltura Entry ID not found in dialog.").css("color", "red");
                new Coral.Alert().set({variant: 'error', header: {innerHTML: 'Error'}, content: {innerHTML: 'Kaltura Entry ID not found. Ensure it is set in the "Video Details" tab.'}}).show().center();
                return;
            }

            if (timecodeMs === "" || parseInt(timecodeMs, 10) < 0) {
                $statusDisplay.text("Error: Please enter a valid non-negative timecode in milliseconds.").css("color", "red");
                 new Coral.Alert().set({variant: 'error', header: {innerHTML: 'Validation Error'}, content: {innerHTML: 'Please enter a valid non-negative timecode in milliseconds.'}}).show().center();
                return;
            }

            $statusDisplay.text("Generating thumbnail...").css("color", "blue");
            $generateButton.prop("disabled", true);

            // Construct the servlet URL using the component's path
            // The componentPath field should be populated with the resource path of the component being edited.
            if (!componentPath) {
                 $statusDisplay.text("Error: Component path not found. Cannot determine servlet URL.").css("color", "red");
                 $generateButton.prop("disabled", false);
                 new Coral.Alert().set({variant: 'error', header: {innerHTML: 'Configuration Error'}, content: {innerHTML: 'Component path not found.'}}).show().center();
                return;
            }
            var servletUrl = componentPath + ".generateThumbnail.json";

            $.ajax({
                url: servletUrl,
                type: "POST",
                data: {
                    // entryId is already part of the component's data, servlet can get it from resource.
                    // However, sending it explicitly can be a good validation.
                    entryId: entryId, // Redundant if servlet uses current resource, but good for clarity/validation
                    timecodeMs: timecodeMs
                    // CSRF token might be needed if not using Granite.csrf.getToken() globally
                    // ":cq_csrf_token": Granite.csrf.getToken()
                },
                success: function(response) {
                    if (response.success) {
                        var successMsg = "Successfully generated and set new thumbnail!";
                        if (response.new_thumbnail_url) {
                            successMsg += " New Thumbnail URL: " + response.new_thumbnail_url;
                        }
                        successMsg += " Please save the dialog and refresh the page to see the updated thumbnail on the component.";
                        $statusDisplay.text(successMsg).css("color", "green");
                        new Coral.Alert().set({variant: 'success', header: {innerHTML: 'Success'}, content: {innerHTML: successMsg}}).show().center();

                        // Optionally, try to update the poster attribute of any video elements on the page
                        // This is a best-effort and might not always work or be desired.
                        // It won't update the Sling Model's data without a page reload.
                        // Example: $('video[poster*="' + entryId + '"]').attr('poster', response.new_thumbnail_url);
                        
                        // A more robust way to refresh component content on the page without full reload
                        // would involve Granite UI's content update mechanisms, which can be complex.
                        // For now, instructing user to save and refresh is simplest.

                    } else {
                        $statusDisplay.text("Error: " + response.message).css("color", "red");
                        new Coral.Alert().set({variant: 'error', header: {innerHTML: 'Generation Failed'}, content: {innerHTML: response.message || 'Unknown error.'}}).show().center();
                    }
                    $generateButton.prop("disabled", false);
                },
                error: function(jqXHR, textStatus, errorThrown) {
                    var errorMsg = "AJAX Error: " + (jqXHR.responseJSON && jqXHR.responseJSON.message ? jqXHR.responseJSON.message : errorThrown);
                    $statusDisplay.text(errorMsg).css("color", "red");
                     new Coral.Alert().set({variant: 'error', header: {innerHTML: 'AJAX Error'}, content: {innerHTML: errorMsg}}).show().center();
                    $generateButton.prop("disabled", false);
                }
            });
        });
    });
})(document, jQuery, Coral);

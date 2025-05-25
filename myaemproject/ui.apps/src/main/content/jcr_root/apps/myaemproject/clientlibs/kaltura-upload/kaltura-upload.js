(function(document, $, Coral) {
    "use strict";

    // Function to read file as Base64
    function getBase64(file) {
        return new Promise((resolve, reject) => {
            const reader = new FileReader();
            reader.readAsDataURL(file);
            reader.onload = () => resolve(reader.result.split(',')[1]); // Remove "data:*/*;base64," prefix
            reader.onerror = error => reject(error);
        });
    }

    $(document).on("foundation-contentloaded", function() {
        $(".kaltura-video-upload-container").each(function() {
            const $container = $(this);
            const componentPath = $container.data("component-path");
            const $fileInput = $container.find(".kaltura-video-file-input");
            const $titleInput = $container.find(".kaltura-video-title-input");
            const $descriptionInput = $container.find(".kaltura-video-description-input");
            const $uploadButton = $container.find(".kaltura-upload-button");
            const $statusDiv = $container.find(".kaltura-upload-status");
            const $fileNameHiddenInput = $container.find(".kaltura-video-filename-hidden");

            let selectedFile = null;

            $fileInput.on("change", function(event) {
                selectedFile = event.target.files[0];
                if (selectedFile) {
                    $fileNameHiddenInput.val(selectedFile.name); // Store filename
                    $statusDiv.text("File selected: " + selectedFile.name);
                } else {
                    $fileNameHiddenInput.val("");
                    $statusDiv.text("");
                }
            });

            $uploadButton.on("click", async function() {
                if (!selectedFile) {
                    $statusDiv.text("Please select a video file first.").css("color", "red");
                    // For Coral3 dialog, show an alert
                    if (window.Granite && window.Granite.author && window.Granite.author.ui) {
                         new Coral.Alert().set({
                            variant: 'error',
                            header: { innerHTML: 'Validation Error' },
                            content: { innerHTML: 'Please select a video file first.'}
                        }).show().center();
                    }
                    return;
                }

                const videoTitle = $titleInput.val();
                const videoDescription = $descriptionInput.val();
                const videoFileName = selectedFile.name; // Use the actual file name

                if (!videoTitle) {
                    $statusDiv.text("Please enter a video title.").css("color", "red");
                     if (window.Granite && window.Granite.author && window.Granite.author.ui) {
                         new Coral.Alert().set({variant: 'error', header: {innerHTML: 'Validation Error'}, content: {innerHTML: 'Please enter a video title.'}}).show().center();
                    }
                    return;
                }

                $statusDiv.text("Uploading...").css("color", "blue");
                $uploadButton.prop("disabled", true);

                try {
                    const base64Video = await getBase64(selectedFile);
                    const servletUrl = componentPath + ".upload.json";

                    $.ajax({
                        url: servletUrl,
                        type: "POST",
                        data: {
                            videoFile: base64Video,
                            videoFilename: videoFileName,
                            videoTitle: videoTitle,
                            videoDescription: videoDescription,
                            // Add CSRF token if AEM instance requires it for POSTs
                            // ":cq_csrf_token": Granite.csrf.getToken()
                        },
                        success: function(response) {
                            if (response.success) {
                                $statusDiv.text("Upload successful! Kaltura Entry ID: " + response.entryId).css("color", "green");
                                // Optionally, update a hidden field in the dialog if in author mode
                                // This is tricky as the dialog might not be open or reloaded.
                                // A page refresh or manual edit might be needed to see it in the dialog.
                                console.log("Kaltura Entry ID:", response.entryId);
                                $container.find(".kaltura-upload-form").hide(); // Hide form on success
                                
                                let successMessageHTML = `<p>Kaltura Video Entry ID: <strong>${response.entryId}</strong></p>`;
                                successMessageHTML += `<p>Title: ${videoTitle}</p>`;
                                if (videoDescription) successMessageHTML += `<p>Description: ${videoDescription}</p>`;
                                
                                // Display thumbnail if URL is provided
                                const $thumbnailDisplayDiv = $container.find(".kaltura-thumbnail-display");
                                $thumbnailDisplayDiv.empty(); // Clear previous thumbnail/message

                                if (response.thumbnailUrl) {
                                    console.log("Displaying thumbnail:", response.thumbnailUrl);
                                    const $img = $("<img>").attr({
                                        src: response.thumbnailUrl,
                                        alt: "Video Thumbnail for " + videoTitle,
                                        style: "max-width: 320px; max-height: 180px; border: 1px solid #ccc; margin-top:10px;"
                                    });
                                    $thumbnailDisplayDiv.append("<p>Uploaded Video Thumbnail:</p>").append($img);
                                } else {
                                    $thumbnailDisplayDiv.append("<p>Thumbnail not available at this time.</p>");
                                }
                                
                                // Prepend success message to status div or another dedicated area
                                $statusDiv.html(successMessageHTML).css("color", "green");


                            } else {
                                $statusDiv.text("Upload failed: " + response.message).css("color", "red");
                                console.error("Upload failed:", response);
                            }
                            $uploadButton.prop("disabled", false);
                        },
                        error: function(jqXHR, textStatus, errorThrown) {
                            let errorMsg = "Upload error: " + (jqXHR.responseJSON && jqXHR.responseJSON.message ? jqXHR.responseJSON.message : errorThrown);
                            $statusDiv.text(errorMsg).css("color", "red");
                            console.error("AJAX error:", textStatus, errorThrown, jqXHR.responseText);
                            $uploadButton.prop("disabled", false);
                        }
                    });

                } catch (error) {
                    $statusDiv.text("Error reading file: " + error.message).css("color", "red");
                    console.error("File reading error:", error);
                    $uploadButton.prop("disabled", false);
                }
            });
        });
    });

})(document, jQuery, Coral);

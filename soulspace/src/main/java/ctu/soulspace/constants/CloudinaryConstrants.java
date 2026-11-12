package ctu.edu.blogAPI.constants;

import lombok.NoArgsConstructor;

@NoArgsConstructor
public class CloudinaryConstrants {
    public static final String ERROR_DELETE_FILE_IN_BLOGS = "Failed to delete {} files from Cloudinary for deleted blog {}: {}";
    public static final String ERROR_DELETE_FILE = "Failed to delete file from Cloudinary: {}";
    public static final String ERROR_UPLOAD_FILE = "Failed to upload file: {}";

    public static final String SUCCESS_DELETE_FILE = "Deleted file successfully: {}";
}

import { useState, useRef, useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { api, apiErrorMessage } from "../../config/api";
import { refreshSession, useAuth } from "../../config/auth";
import { DEFAULT_AVATAR_URL } from "../../config/assets";
import style from "./PostPage.module.css";
import { toast } from "sonner";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/Components/ui/select";
import { Button } from "@/Components/ui/button";
const MAX_IMAGES = 10;
const MAX_IMAGE_BYTES = 10 * 1024 * 1024;
const MAX_CONTENT_LENGTH = 5_000;
const UploadIcon = () => (
  <svg
    xmlns="http://www.w3.org/2000/svg"
    width="24"
    height="24"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth="2"
    strokeLinecap="round"
    strokeLinejoin="round"
    className="w-6 h-6 block text-gray-700 group-hover:text-blue-600 transition-colors"
  >
    <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
    <polyline points="17 8 12 3 7 8" />
    <line x1="12" x2="12" y1="3" y2="15" />
  </svg>
);
export default function PostPage() {
  const [body, setBody] = useState("");
  const [publish, setPublish] = useState(true);
  const [files, setFiles] = useState([]);
  const [previewUrls, setPreviewUrls] = useState([]);
  const [progress, setProgress] = useState(0);
  const { user, ready, error: authError } = useAuth();
  const navigate = useNavigate();
  const fileInputRef = useRef(null);
  const [isSubmit, setIsSubmit] = useState(false);
  useEffect(() => {
    const urls = files.map((file) => URL.createObjectURL(file));
    setPreviewUrls(urls);
    return () => urls.forEach((url) => URL.revokeObjectURL(url));
  }, [files]);

  const handleFileChange = (e) => {
    const selectedFiles = Array.from(e.target.files);
    e.target.value = "";
    if (selectedFiles.length > MAX_IMAGES || selectedFiles.some((file) =>
      !["image/jpeg", "image/png"].includes(file.type) || file.size > MAX_IMAGE_BYTES)) {
      toast.error("Chọn tối đa 10 ảnh JPEG/PNG, mỗi ảnh không quá 10 MiB.");
      return;
    }
    setFiles(selectedFiles);
  };

  // ✅ Xóa một hình
  const handleRemoveImage = (index) => {
    const newFiles = [...files];
    newFiles.splice(index, 1);
    setFiles(newFiles);

    if (newFiles.length === 0 && fileInputRef.current) {
      fileInputRef.current.value = "";
    }
  };

  const postNews = async () => {
    if (isSubmit) return;
    if (!user) {
      toast.error("Vui lòng đăng nhập để đăng bài.");
      return;
    }
    if ((!body.trim() && files.length === 0) || Array.from(body.trim()).length > MAX_CONTENT_LENGTH) {
      toast.error("Thêm nội dung hoặc ảnh. Nội dung tối đa 5.000 ký tự.");
      return;
    }
    setIsSubmit(true);
    const loadingToast = toast.loading("Uploading...");

    try {
      const formData = new FormData();
      formData.append("content", body);
      formData.append("published", publish ? "true" : "false");
      files.forEach((file) => formData.append("files", file));

      const response = await api.post("/api/v1/blogs", formData, {
        onUploadProgress: (e) => {
          const percent = e.total ? Math.round((e.loaded * 100) / e.total) : 0;
          setProgress(percent);
        },
      });

      toast.success("Post successful!", { id: loadingToast });
      if (response.data.failedFiles?.length) {
        toast.warning(`Bài đã được lưu; ${response.data.failedFiles.length} ảnh chưa tải lên được.`);
      }
      navigate(publish ? "/" : "/user");
    } catch (error) {
      toast.error(apiErrorMessage(error, "Không thể đăng bài. Vui lòng thử lại."), { id: loadingToast });
    } finally {
      setProgress(0);
      setIsSubmit(false);
    }
  };

  // Khi nhấn "Post"
  const handleSubmit = (e) => {
    e.preventDefault();

    postNews();
  };

  if (!ready) {
    return <section className={style.state}><p role="status">Đang kiểm tra phiên đăng nhập...</p></section>;
  }
  if (!user) {
    if (!authError) return null;
    return (
      <section className={style.state}>
        <h1 className={style.stateTitle}>Chưa thể kiểm tra phiên đăng nhập</h1>
        <p className={style.stateDescription} role="alert">Vui lòng kiểm tra kết nối rồi thử lại.</p>
        <Button onClick={() => refreshSession().catch(() => {})}>Thử lại</Button>
      </section>
    );
  }

  return (
    <div className={style.boxlayout}>
      {/* --- Tiêu đề --- */}
      <div className={style.infor}>
        <span style={{ fontSize: 17, fontWeight: 560, marginBottom: 15 }}>
          Make your new post
        </span>
      </div>

      {/* --- Nội dung post --- */}
          <div className={style["post-content"]}>
            <div className={style.header_post}>
              <div className={style.avatar_mini_wrapper}>
                <img src={user.avatarUrl || DEFAULT_AVATAR_URL} alt="AvatarUser" className={style.avatar } />
              </div>
              <strong>
                <span>
                  @{user.username}
                </span>
              </strong>
            </div>

            <textarea
              placeholder="Start a new post..."
              className={style.input}
              value={body}
              disabled={isSubmit}
              onChange={(e) => setBody(e.target.value)}
            />
          </div>

          <div className={style.previewContainer}>
            {previewUrls.map((url, index) => (
              <div key={index} className={style.previewWrapper}>
                <img
                  src={url}
                  alt={`preview-${index}`}
                  className={style.previewImage}
                />
                <button
                  type="button"
                  className={style.removeButton}
                  onClick={() => handleRemoveImage(index)}
                >
                  ✕
                </button>
              </div>
            ))}
          </div>

          {/* --- Tuỳ chọn & nút Post --- */}
          <div className={style.option}>
            <div className={style.leftbuttons}>
              <Select
                onValueChange={(value) => {
                  setPublish(value === "true");
                }}
                defaultValue={String(publish)}
              >
                <SelectTrigger className="w-[180px] border-accent-foreground cursor-pointer">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="true">
                    <span style={{ fontWeight: "bold" }}>Publish</span>
                  </SelectItem>
                  <SelectItem value="false">
                    <span style={{ fontWeight: "bold" }}>Private</span>
                  </SelectItem>
                </SelectContent>
              </Select>
              <label
                htmlFor="fileInput"
                className="group cursor-pointer  rounded-lg border flex items-center justify-center bg-white shadow-sm transition-all h-10 w-10"
              >
                <UploadIcon />
              </label>

              <input
                id="fileInput"
                type="file"
                accept="image/jpeg,image/png"
                disabled={isSubmit}
                multiple
                ref={fileInputRef}
                onChange={handleFileChange}
                className={style.fileInput}
              />
            </div>
            <Button onClick={handleSubmit} disabled={isSubmit} className="w-30 h-10 cursor-pointer">
              Post
            </Button>
          </div>

          {progress > 0 && progress < 100 && (
            <div
              className={style.progressBar}
              role="progressbar"
              aria-label="Upload progress"
              aria-valuemin={0}
              aria-valuemax={100}
              aria-valuenow={progress}
            >
              <div
                className={style.progressFill}
                style={{ width: `${progress}%` }}
              />
            </div>
          )}
    </div>
  );
}

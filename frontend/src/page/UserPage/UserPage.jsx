import { useEffect, useState, useRef } from "react";
import axios from "axios";
import { buildApiUrl } from "../../config/api";
import { assetUrl } from "../../config/assets";
import Timestamp from "../../Components/Timestamp";
import { toast } from "sonner";
import { useNavigate } from "react-router-dom";
import "./UserPage.css";
import Edit from "../../Components/EditProfile/Edit";
import EditAvt from "../../Components/EditAvatar/EditAvt";
import { BsThreeDots } from "react-icons/bs";
import { Button } from "@/Components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/Components/ui/dropdown-menu";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@/Components/ui/alert-dialog";
import { MoreHorizontalIcon, UploadIcon } from "lucide-react";
import { Field, FieldGroup, FieldLabel } from "@/Components/ui/field";
import { Textarea } from "@/Components/ui/textarea";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/Components/ui/select";

export default function UserPage() {
  const [activeTab, setActiveTab] = useState("posts");
  const [showEdit, setShowEdit] = useState(false);
  const [showEditAvt, setShowEditAvt] = useState(false);
  const [dataUser, setDataUser] = useState(null);
  const [isLogin, setIsLogin] = useState(false);
  const navigate = useNavigate();
  const [isDelete, setIsDelete] = useState(false);
  const [userBlog, setUserBlogs] = useState([]);
  const [sharedBlogs, setSharedBlogs] = useState([]);
  const [editingContent, setEditingContent] = useState("");
  const [editingImage, setEditingImage] = useState([]);
  const [publish, setPublish] = useState(true);
  const [newImages, setNewImages] = useState([]);
  const fileInputRef = useRef(null);

  const [menuOpen, setMenuOpen] = useState(false);
  const menuRef = useRef(null);

  const fetchDataUser = async () => {
    try {
      const userId = String(localStorage.getItem("userId")).replaceAll('"', "");
      if (!userId) {
        setIsLogin(false);
        localStorage.removeItem("isLoggedIn");
        console.error("User ID not found in LocalStorage.");
        return;
      }

      const tempData = await axios.get(
        buildApiUrl(`/api/v1/users/${userId}`)
      );

      const islogined = Boolean(localStorage.getItem("isLoggedIn"));
      setIsLogin(islogined);

      if (!islogined) {
        return;
      }

      setDataUser(tempData.data);
      console.log("Lấy dữ liệu user thành công");
    } catch (error) {
      console.log("Lỗi khi lấy dữ liệu user", error);
      toast.error("Cannot get data user");
      setIsLogin(false);
      localStorage.removeItem("isLoggedIn");
      localStorage.removeItem("userId");
    }
  };

  const deleteDataUser = async () => {
    try {
      const userId = String(localStorage.getItem("userId")).replaceAll('"', "");
      if (!userId) {
        toast.error("User session error. Please log in again.");
        return;
      }

      await axios.delete(buildApiUrl(`/api/v1/users/${userId}`));
      console.log("Xóa user thành công");
      toast.success("Delete user successfully!");
      localStorage.removeItem("isLoggedIn");
      localStorage.removeItem("userId");
      navigate("/");
    } catch (error) {
      console.log("Không thể xóa user", error);
      toast.error("Cannot delete user");
    }
  };

  const getUserPost = async () => {
    try {
      const userId = String(localStorage.getItem("userId")).replaceAll('"', "");
      if (!userId) {
        return;
      }

      const blogs = await axios.get(
        buildApiUrl(`/api/v1/blogs/${userId}/all`)
      );

      if (Array.isArray(blogs.data)) {
        setUserBlogs(blogs.data);
      } else {
        setUserBlogs([]);
        console.warn("Expected an array of blogs, but received:", blogs.data);
      }

      console.log("Lay bai viet user thanh cong");
    } catch (error) {
      console.log("Khong the lay cai bai post cua user", error);
      toast.error("Cannot get user's blogs");
    }
  };

  const getSharedBlogs = async () => {
    try {
      const userId = String(localStorage.getItem("userId")).replaceAll('"', "");
      if (!userId) {
        return;
      }

      const response = await axios.get(
        buildApiUrl(`/api/v1/users/${userId}/shared-blogs`)
      );

      if (Array.isArray(response.data)) {
        setSharedBlogs(response.data);
      } else {
        setSharedBlogs([]);
      }
    } catch (error) {
      console.log("Khong the lay bai viet da chia se", error);
      setSharedBlogs([]);
    }
  };

  useEffect(() => {
    const init = async () => {
      await fetchDataUser();
    };
    init();
  }, []);

  useEffect(() => {
    if (dataUser && isLogin) {
      getUserPost();
      getSharedBlogs();
    }
  }, [dataUser, isLogin]);

  useEffect(() => {
    if (isDelete) {
      deleteDataUser();
      setIsDelete(false);
    }
  }, [isDelete]);

  const handleSave = (updatedUser) => {
    setDataUser(updatedUser);
    setShowEdit(false);
    setShowEditAvt(false);
  };

  const handleRemoveImage = (index) => {
    const newFiles = [...editingImage];
    newFiles.splice(index, 1);
    setEditingImage(newFiles);
  };

  const handleRemoveNewImage = (index) => {
    const updatedFiles = [...newImages];
    updatedFiles.splice(index, 1);
    setNewImages(updatedFiles);
  };

  const handleFileChange = (event) => {
    const files = Array.from(event.target.files);
    if (files.length > 0) {
      setNewImages(files);
      setEditingImage([]);
    }
  };

  const handleDelete = () => {
    if (
      window.confirm(
        "Are you sure you want to delete your account? This action cannot be undone."
      )
    ) {
      setIsDelete(true);
    }
  };

  const handleLogout = () => {
    localStorage.removeItem("isLoggedIn");
    localStorage.removeItem("userId");
    setIsLogin(false);
    setDataUser(null);
    setUserBlogs([]);
    setSharedBlogs([]);
    toast.success("Signed out successfully!");
    navigate("/");
  };

  const handleDeletePost = async (blogId) => {
    const loadingToast = toast.loading("Deleting blog...");
    try {
      await axios.delete(buildApiUrl(`/api/v1/blogs/${blogId}`));
      console.log("Xoa blog thanh cong");
      toast.success("Delete blog successful", { id: loadingToast });
      setUserBlogs((currentBlogs) =>
        currentBlogs.filter((blog) => blog.id !== blogId)
      );
    } catch (error) {
      console.log("Khong the xoa blog", error);
      toast.error("Cannot delete blog", { id: loadingToast });
    }
  };

  const handleRemoveSharedPost = async (blogId) => {
    const loadingToast = toast.loading("Removing shared post...");
    try {
      const userId = String(localStorage.getItem("userId") || "").replaceAll(
        '"',
        ""
      );

      if (!userId) {
        toast.error("User session error. Please log in again.", {
          id: loadingToast,
        });
        return;
      }

      await axios.delete(
        buildApiUrl(`/api/v1/users/${userId}/shared-blogs/${blogId}`)
      );

      setSharedBlogs((prev) => prev.filter((post) => post.blogId !== blogId));
      toast.success("Removed shared post successfully", { id: loadingToast });
    } catch (error) {
      console.log("Khong the xoa bai da chia se", error);
      toast.error("Cannot remove shared post", { id: loadingToast });
    }
  };

  const handleUpdatePost = async (blogId, originalImageUrls) => {
    const loadingToast = toast.loading("Updating post...");
    try {
      const formData = new FormData();
      formData.append("blogId", blogId);
      formData.append("content", editingContent);

      const removedUrls = originalImageUrls.filter(
        (originalUrl) => !editingImage.includes(originalUrl)
      );
      removedUrls.forEach((url) => {
        formData.append("removeImagesUrl", url);
      });

      newImages.forEach((file) => {
        formData.append("newImages", file);
      });

      await axios.patch(buildApiUrl("/api/v1/blogs-details"), formData);
      await axios.patch(buildApiUrl("/api/v1/blogs/access"), {
        blogId: blogId,
        published: publish,
      });

      console.log("Cap nhat blog thanh cong");
      toast.success("Update post successful", { id: loadingToast });

      getUserPost();
      setNewImages([]);
    } catch (error) {
      console.log("Khong the cap nhat blog", error);
      toast.error("Cannot update post", { id: loadingToast });
    }
  };

  const handleAvatarUpdate = (newUserData) => {
    setDataUser(newUserData);
  };

  return (
    <div className="user-page">
      <nav className="nav-bar">
        <h1>
          <span>Profile</span>
        </h1>

        {isLogin && (
          <div className="menu-wrapper" ref={menuRef}>
            <button className="btn" onClick={() => setMenuOpen(!menuOpen)}>
              <BsThreeDots />
            </button>

            {menuOpen && (
              <div className="dropdown-menu">
                <Button onClick={handleLogout} className="signout-btn">
                  Sign out
                </Button>
                <Button onClick={handleDelete} className="deleteacc-btn">
                  Delete account
                </Button>
              </div>
            )}
          </div>
        )}
      </nav>

      {isLogin ? (
        <>
          {!dataUser ? (
            <div className="loading-container">
              <p>Loading profile...</p>
            </div>
          ) : (
            <>
              <nav className="profile">
                <div className="profile-in4">
                  <h1>{dataUser.fullname}</h1>
                  <p className="name">@{dataUser.username}</p>
                </div>

                <div className="profile-avt">
                  <div className="avatar-wrapper">
                    <img
                      src={dataUser.userAvatarUrl}
                      alt="avatar"
                      className="avt"
                    />
                    <button
                      className="edit-avatar-btn"
                      onClick={() => setShowEditAvt(true)}
                      title="Edit Avatar"
                    >
                      ✎
                    </button>
                  </div>
                </div>

                <button
                  className="edit-btn"
                  onClick={() => setShowEdit(true)}
                >
                  Edit profile
                </button>
              </nav>

              <div className="tab">
                <button
                  className={`tab-btn ${activeTab === "posts" ? "active" : ""}`}
                  onClick={() => setActiveTab("posts")}
                >
                  My posts ({userBlog.length})
                </button>

                <button
                  className={`tab-btn ${activeTab === "replay" ? "active" : ""}`}
                  onClick={() => {
                    setActiveTab("replay");
                    getSharedBlogs();
                  }}
                >
                  replay ({sharedBlogs.length})
                </button>
              </div>

              <div className="layout">
                {activeTab === "posts" ? (
                  userBlog.length > 0 ? (
                    [...userBlog].reverse().map((post) => (
                      <div key={post.id} className="box">
                        <div className="post-content">
                          <div className="header_post">
                            <div className="avatar_mini_wrapper">
                              <img
                                src={post.userAvatarUrl || assetUrl("default-avatar.png")}
                                alt="User Avatar"
                                className="avatar"
                              />
                            </div>

                            <div className="post-meta">
                              <div className="user-box" title={post.username}>
                                {post.username ? "@" + post.username : "Unknown user"}
                              </div>
                              <Timestamp value={post.createdAt} className="post-time" />
                            </div>

                            <div className="modifileButton">
                              <DropdownMenu modal={false}>
                                <DropdownMenuTrigger asChild>
                                  <Button
                                    variant="outline"
                                    aria-label="Open menu"
                                    size="icon-sm"
                                  >
                                    <MoreHorizontalIcon />
                                  </Button>
                                </DropdownMenuTrigger>

                                <DropdownMenuContent className="w-40" align="end">
                                  <DropdownMenuGroup>
                                    <AlertDialog>
                                      <AlertDialogTrigger asChild>
                                        <DropdownMenuItem
                                          onSelect={(e) => {
                                            e.preventDefault();
                                            setEditingContent(post.content);
                                            setEditingImage(post.imageContentUrls);
                                            setPublish(post.published ?? true);
                                            setNewImages([]);
                                          }}
                                        >
                                          <span>Update</span>
                                        </DropdownMenuItem>
                                      </AlertDialogTrigger>

                                      <AlertDialogContent>
                                        <AlertDialogHeader>
                                          <AlertDialogTitle>
                                            Update post
                                          </AlertDialogTitle>
                                          <AlertDialogDescription>
                                            Make changes to your post and upload new
                                            images.
                                          </AlertDialogDescription>
                                        </AlertDialogHeader>

                                        <div className="flex-1 min-h-0 overflow-y-auto pr-4">
                                          <FieldGroup className="py-3">
                                            <Field>
                                              <Textarea
                                                id="content"
                                                name="content"
                                                value={editingContent}
                                                onChange={(e) =>
                                                  setEditingContent(e.target.value)
                                                }
                                              />

                                              {newImages.length > 0 ? (
                                                <>
                                                  <FieldLabel>New Images</FieldLabel>
                                                  <div className="image-preview-container">
                                                    {newImages.map((file, index) => (
                                                      <div
                                                        key={index}
                                                        className="previewWrapper"
                                                      >
                                                        <img
                                                          src={URL.createObjectURL(file)}
                                                          className="previewImage"
                                                          alt={file.name}
                                                        />
                                                        <button
                                                          type="button"
                                                          className="removeButton"
                                                          onClick={() =>
                                                            handleRemoveNewImage(index)
                                                          }
                                                        >
                                                          ✕
                                                        </button>
                                                      </div>
                                                    ))}
                                                  </div>
                                                </>
                                              ) : (
                                                <>
                                                  <FieldLabel>Current Images</FieldLabel>
                                                  <div className="image-preview-container">
                                                    {Array.isArray(editingImage) &&
                                                      editingImage.map((url, index) => (
                                                        <div
                                                          key={index}
                                                          className="previewWrapper"
                                                        >
                                                          <img
                                                            src={url}
                                                            className="previewImage"
                                                            alt={`preview-${index}`}
                                                          />
                                                          <button
                                                            type="button"
                                                            className="removeButton"
                                                            onClick={() =>
                                                              handleRemoveImage(index)
                                                            }
                                                          >
                                                            ✕
                                                          </button>
                                                        </div>
                                                      ))}
                                                  </div>
                                                </>
                                              )}
                                            </Field>
                                          </FieldGroup>
                                        </div>

                                        <AlertDialogFooter>
                                          <div className="flex-1 flex-row justify-between gap-2.5">
                                            <div className="flex items-center gap-2.5">
                                              <Select
                                                onValueChange={(value) => {
                                                  setPublish(value === "true");
                                                }}
                                                defaultValue={String(publish)}
                                              >
                                                <SelectTrigger className="w-[120px] border-accent-foreground cursor-pointer">
                                                  <SelectValue />
                                                </SelectTrigger>
                                                <SelectContent>
                                                  <SelectItem value="true">
                                                    <span>Publish</span>
                                                  </SelectItem>
                                                  <SelectItem value="false">
                                                    <span>Private</span>
                                                  </SelectItem>
                                                </SelectContent>
                                              </Select>

                                              <label
                                                htmlFor="fileInput"
                                                className="group cursor-pointer rounded-lg border flex items-center justify-center bg-white shadow-sm transition-all h-10 w-10"
                                              >
                                                <UploadIcon />
                                              </label>

                                              <input
                                                id="fileInput"
                                                type="file"
                                                multiple
                                                ref={fileInputRef}
                                                onChange={handleFileChange}
                                                className="hidden"
                                              />
                                            </div>
                                          </div>

                                          <AlertDialogCancel>
                                            Cancel
                                          </AlertDialogCancel>
                                          <AlertDialogAction
                                            onClick={() =>
                                              handleUpdatePost(
                                                post.id,
                                                post.imageContentUrls
                                              )
                                            }
                                          >
                                            Update
                                          </AlertDialogAction>
                                        </AlertDialogFooter>
                                      </AlertDialogContent>
                                    </AlertDialog>

                                    <AlertDialog>
                                      <AlertDialogTrigger asChild>
                                        <DropdownMenuItem
                                          onSelect={(e) => e.preventDefault()}
                                        >
                                          <span style={{ color: "red" }}>
                                            Delete
                                          </span>
                                        </DropdownMenuItem>
                                      </AlertDialogTrigger>

                                      <AlertDialogContent>
                                        <AlertDialogHeader>
                                          <AlertDialogTitle>
                                            Are you sure?
                                          </AlertDialogTitle>
                                          <AlertDialogDescription>
                                            This action cannot be undone.
                                          </AlertDialogDescription>
                                        </AlertDialogHeader>

                                        <AlertDialogFooter>
                                          <AlertDialogCancel>
                                            Cancel
                                          </AlertDialogCancel>
                                          <AlertDialogAction
                                            onClick={() => handleDeletePost(post.id)}
                                          >
                                            Delete
                                          </AlertDialogAction>
                                        </AlertDialogFooter>
                                      </AlertDialogContent>
                                    </AlertDialog>
                                  </DropdownMenuGroup>
                                </DropdownMenuContent>
                              </DropdownMenu>
                            </div>
                          </div>
                        </div>

                        <div className="desc-box">{post.content}</div>

                        {Array.isArray(post.imageContentUrls) &&
                          post.imageContentUrls.map((url, index) => (
                            <img
                              key={index}
                              src={url}
                              className="picture"
                              alt={`Post image ${index}`}
                            />
                          ))}

                      </div>
                    ))
                  ) : (
                    <div className="empty-state">Bạn chưa có bài viết nào.</div>
                  )
                ) : sharedBlogs.length > 0 ? (
                  sharedBlogs.map((post, index) => (
                    <div
                      key={`${post.blogId}-${post.sharedAt || index}`}
                      className="box"
                    >
                      <div className="shared-header">
                        <div className="shared-meta">Shared post</div>
                        <button
                          type="button"
                          className="remove-shared-btn"
                          onClick={() => handleRemoveSharedPost(post.blogId)}
                        >
                          Xóa
                        </button>
                      </div>

                      <div className="post-content">
                        <div className="header_post">
                          <div className="avatar_mini_wrapper">
                            <img
                              src={post.userAvatarUrl || assetUrl("default-avatar.png")}
                              alt="User Avatar"
                              className="avatar"
                            />
                          </div>

                          <div className="post-meta">
                            <div className="user-box" title={post.username}>
                              {post.username ? "@" + post.username : "Unknown user"}
                            </div>
                            <Timestamp value={post.sharedAt} className="post-time" />
                          </div>
                        </div>
                      </div>

                      <div className="desc-box">{post.content}</div>

                      {Array.isArray(post.imageContentUrls) &&
                        post.imageContentUrls.map((url, imageIndex) => (
                          <img
                            key={imageIndex}
                            src={url}
                            className="picture"
                            alt={`Shared post image ${imageIndex}`}
                          />
                        ))}

                      <div className="shared-time-group">
                        {post.originalCreatedAt && (
                          <span className="shared-time-item">
                            Bài gốc: <Timestamp value={post.originalCreatedAt} />
                          </span>
                        )}

                      </div>

                      <div className="status-bar readonly-status-bar">
                        <button
                          type="button"
                          className="status-btn btn-like-static"
                          aria-label="Like count"
                          disabled
                        >
                          <span className="status-icon like-status-icon" />
                        </button>
                        <span>{post.likesCount || 0}</span>

                        <button
                          type="button"
                          className="status-btn btn-comment-static"
                          aria-label="Comment count"
                          disabled
                        >
                          <span className="status-icon comment-status-icon" />
                        </button>
                        <span>{post.commentsCount || 0}</span>

                        <button
                          type="button"
                          className="status-btn btn-share-static"
                          aria-label="Share count"
                          disabled
                        >
                          <span className="status-icon share-status-icon" />
                        </button>
                        <span>{post.sharesCount || 0}</span>
                      </div>
                    </div>
                  ))
                ) : (
                  <div className="empty-state">
                    Bạn chưa chia sẻ bài viết nào.
                  </div>
                )}
              </div>

              {showEdit && (
                <Edit
                  user={dataUser}
                  onClose={() => setShowEdit(false)}
                  onSave={handleSave}
                />
              )}

              {showEditAvt && (
                <EditAvt
                  user={dataUser}
                  currentAvatar={dataUser.userAvatarUrl}
                  onClose={() => setShowEditAvt(false)}
                  onSave={handleAvatarUpdate}
                />
              )}
            </>
          )}
        </>
      ) : (
        <div className="logged-out-container">
          <h2>You are not signed in</h2>
          <p>Please sign in or sign up to view your profile.</p>
        </div>
      )}
    </div>
  );
}

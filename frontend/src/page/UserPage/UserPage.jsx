import { useCallback, useEffect, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { MoreHorizontal, Plus, LockKeyhole, Globe, LogOut, Settings, ArrowUpRight } from "lucide-react";
import { api, apiErrorMessage, buildApiUrl } from "../../config/api";
import { clearSession, refreshSession, signOut, useAuth } from "../../config/auth";
import { DEFAULT_AVATAR_URL } from "../../config/assets";
import { toast } from "sonner";
import Timestamp from "../../Components/Timestamp";
import Edit from "../../Components/EditProfile/Edit";
import EditAvt from "../../Components/EditAvatar/EditAvt";
import { Button } from "../../Components/ui/button";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator, DropdownMenuTrigger } from "../../Components/ui/dropdown-menu";
import { AlertDialog, AlertDialogContent, AlertDialogHeader, AlertDialogTitle, AlertDialogDescription, AlertDialogFooter } from "../../Components/ui/alert-dialog";
import "./UserPage.css";

const MAX_IMAGES = 10;
const MAX_IMAGE_BYTES = 10 * 1024 * 1024;
const MAX_CONTENT_LENGTH = 5_000;
const imageUrl = (url) => url?.startsWith("/api/") ? buildApiUrl(url) : url || DEFAULT_AVATAR_URL;

function PostEditor({ post, onClose, onSave }) {
  const [content, setContent] = useState(post.content || "");
  const [published, setPublished] = useState(post.published);
  const [remaining, setRemaining] = useState(post.imageContentUrls || []);
  const [files, setFiles] = useState([]);
  const [previews, setPreviews] = useState([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  useEffect(() => {
    const urls = files.map((file) => URL.createObjectURL(file));
    setPreviews(urls);
    return () => urls.forEach((url) => URL.revokeObjectURL(url));
  }, [files]);

  function select(event) {
    const selected = Array.from(event.target.files);
    event.target.value = "";
    if (remaining.length + files.length + selected.length > MAX_IMAGES || selected.some((file) => !["image/jpeg", "image/png"].includes(file.type) || file.size > MAX_IMAGE_BYTES)) {
      setError("Tối đa 10 ảnh JPEG/PNG, mỗi ảnh không quá 10 MiB.");
      return;
    }
    setError("");
    setFiles((current) => [...current, ...selected]);
  }

  async function submit(event) {
    event.preventDefault();
    if (busy) return;
    if ((!content.trim() && !remaining.length && !files.length) || Array.from(content.trim()).length > MAX_CONTENT_LENGTH) {
      setError("Bài viết cần nội dung hoặc ảnh; nội dung tối đa 5.000 ký tự.");
      return;
    }
    setBusy(true);
    setError("");
    const body = new FormData();
    body.append("blogId", post.id);
    body.append("content", content);
    body.append("published", String(published));
    (post.imageContentUrls || []).filter((url) => !remaining.includes(url)).forEach((url) => body.append("removeImagesUrl", url));
    files.forEach((file) => body.append("newImages", file));
    try {
      const { data } = await api.patch("/api/v1/blogs-details", body);
      onSave({ ...post, content: data.content, published: data.published, imageContentUrls: data.successUrls, updatedAt: data.updatedAt });
      if (data.failedUploadFiles?.length) toast.warning(`Đã lưu bài. Có ${data.failedUploadFiles.length} ảnh tải lên thất bại.`);
      else toast.success("Đã lưu bài viết.");
      onClose();
    } catch (failure) {
      setError(apiErrorMessage(failure));
    } finally { setBusy(false); }
  }

  return <AlertDialog open onOpenChange={(open) => { if (!open && !busy) onClose(); }}>
    <AlertDialogContent className="profile-dialog">
      <AlertDialogHeader>
        <AlertDialogTitle>Chỉnh sửa bài viết</AlertDialogTitle>
        <AlertDialogDescription>Nội dung, ảnh và quyền xem được lưu cùng nhau.</AlertDialogDescription>
      </AlertDialogHeader>
      <form className="profile-form" onSubmit={submit} aria-busy={busy}>
        <label>Nội dung<textarea autoFocus value={content} onChange={(event) => setContent(event.target.value)} disabled={busy} /></label>
        <label>Ai có thể xem?<select value={String(published)} onChange={(event) => setPublished(event.target.value === "true")} disabled={busy}>
          <option value="true">Công khai</option><option value="false">Chỉ mình tôi</option>
        </select></label>
        {(remaining.length > 0 || files.length > 0) && <div className="profile-edit-images">
          {remaining.map((url) => <figure key={url}><img src={imageUrl(url)} alt="Ảnh trong bài" /><button type="button" disabled={busy} onClick={() => setRemaining((current) => current.filter((item) => item !== url))}>Gỡ ảnh</button></figure>)}
          {files.map((file, index) => <figure key={`${file.name}-${index}`}><img src={previews[index]} alt={file.name} /><button type="button" disabled={busy} onClick={() => setFiles((current) => current.filter((_, position) => position !== index))}>Gỡ ảnh mới</button></figure>)}
        </div>}
        <label>Thêm ảnh<input type="file" multiple accept="image/jpeg,image/png" onChange={select} disabled={busy} /></label>
        <p className="profile-form-hint">{remaining.length + files.length}/10 ảnh · Chỉ gỡ ảnh khi bạn lưu thay đổi.</p>
        {error && <p role="alert" className="profile-form-error">{error}</p>}
        <AlertDialogFooter><Button type="button" variant="outline" disabled={busy} onClick={onClose}>Hủy</Button><Button type="submit" disabled={busy}>{busy ? "Đang lưu…" : "Lưu bài viết"}</Button></AlertDialogFooter>
      </form>
    </AlertDialogContent>
  </AlertDialog>;
}

export default function UserPage() {
  const { user: sessionUser, ready } = useAuth();
  const id = sessionUser?.id;
  const navigate = useNavigate();
  const [profile, setProfile] = useState(null);
  const [posts, setPosts] = useState([]);
  const [shares, setShares] = useState([]);
  const [errors, setErrors] = useState({});
  const [loading, setLoading] = useState(true);
  const [tab, setTab] = useState("posts");
  const [dialog, setDialog] = useState(null);
  const [currentPassword, setCurrentPassword] = useState("");
  const [actionError, setActionError] = useState("");
  const [busy, setBusy] = useState(false);

  const load = useCallback(async (signal) => {
    if (!id) return;
    setLoading(true);
    const results = await Promise.allSettled([
      api.get(`/api/v1/users/${id}`, { signal }),
      api.get(`/api/v1/blogs/${id}/all`, { signal }),
      api.get(`/api/v1/users/${id}/shared-blogs`, { signal }),
    ]);
    if (signal?.aborted) return;
    const nextErrors = {};
    const names = ["profile", "posts", "shares"];
    const setters = [setProfile, setPosts, setShares];
    results.forEach((result, index) => {
      if (result.status === "fulfilled") setters[index](result.value.data);
      else nextErrors[names[index]] = apiErrorMessage(result.reason, "Không thể tải dữ liệu. Hãy thử lại.");
    });
    setErrors(nextErrors);
    setLoading(false);
  }, [id]);

  useEffect(() => {
    const controller = new AbortController();
    setProfile(null);
    setPosts([]);
    setShares([]);
    setDialog(null);
    setCurrentPassword("");
    if (ready && id) load(controller.signal);
    return () => controller.abort();
  }, [id, ready, load]);

  function closeDialog() {
    if (busy) return;
    setDialog(null);
    setActionError("");
    setCurrentPassword("");
  }

  function savedProfile(data) {
    setProfile((current) => ({ ...current, ...data }));
    setPosts((current) => current.map((post) => ({ ...post, username: data.username, userAvatarUrl: data.userAvatarUrl })));
    setShares((current) => current.map((post) => post.originalUserId === id ? { ...post, username: data.username, userAvatarUrl: data.userAvatarUrl } : post));
    refreshSession().catch(() => toast.warning("Hồ sơ đã lưu. Không thể làm mới phiên; hãy tải lại trang."));
  }

  function reloadShares() {
    api.get(`/api/v1/users/${id}/shared-blogs`)
      .then(({ data }) => { setShares(data); setErrors((current) => ({ ...current, shares: null })); })
      .catch((failure) => setErrors((current) => ({ ...current, shares: apiErrorMessage(failure) })));
  }

  function savedPost(post) {
    setPosts((current) => current.map((item) => item.id === post.id ? post : item));
    reloadShares();
  }

  async function logout() {
    if (busy) return;
    setBusy(true);
    try { await signOut(); navigate("/signin"); }
    catch (failure) { toast.error(apiErrorMessage(failure)); }
    finally { setBusy(false); }
  }

  async function confirm(event) {
    event.preventDefault();
    if (busy) return;
    setBusy(true);
    setActionError("");
    try {
      if (dialog.type === "account") {
        await api.delete(`/api/v1/users/${id}`, { data: { currentPassword } });
        clearSession();
        toast.success("Đã xóa tài khoản.");
        navigate("/signin", { replace: true });
      } else if (dialog.type === "delete") {
        await api.delete(`/api/v1/blogs/${dialog.post.id}`);
        setPosts((current) => current.filter((post) => post.id !== dialog.post.id));
        setShares((current) => current.filter((post) => post.blogId !== dialog.post.id));
        toast.success("Đã xóa bài viết.");
      } else {
        await api.delete(`/api/v1/users/${id}/shared-blogs/${dialog.post.blogId}`);
        setShares((current) => current.filter((post) => post.blogId !== dialog.post.blogId));
        toast.success("Đã bỏ chia sẻ.");
      }
      setDialog(null);
      setCurrentPassword("");
    } catch (failure) { setActionError(apiErrorMessage(failure)); }
    finally { setBusy(false); }
  }

  async function access(post) {
    if (busy) return;
    setBusy(true);
    try {
      const { data } = await api.patch("/api/v1/blogs/access", { blogId: post.id, published: !post.published });
      setPosts((current) => current.map((item) => item.id === post.id ? { ...item, published: data.published, updatedAt: data.updatedAt } : item));
      reloadShares();
      toast.success(data.published ? "Bài viết đã công khai." : "Chỉ bạn có thể xem bài viết này.");
    } catch (failure) { toast.error(apiErrorMessage(failure)); }
    finally { setBusy(false); }
  }

  if (!ready) return <div className="profile-state" role="status">Đang kiểm tra phiên đăng nhập…</div>;
  if (!id) return <div className="profile-state"><h1>Trang cá nhân</h1><p>Đăng nhập để xem và quản lý hồ sơ của bạn.</p><Button asChild><Link to="/signin">Đăng nhập</Link></Button></div>;
  if (!profile) return <div className="profile-state" aria-busy={loading}>{loading ? "Đang tải hồ sơ…" : <><p role="alert">{errors.profile || "Chưa tải được hồ sơ."}</p><Button onClick={() => load()}>Thử lại</Button></>}</div>;

  const sharedTab = tab === "shares";
  const items = sharedTab ? shares : posts;
  return <main className="profile-page">
    <header className="profile-topbar"><h1>Trang cá nhân</h1><DropdownMenu><DropdownMenuTrigger asChild><Button variant="ghost" size="icon" aria-label="Cài đặt tài khoản" disabled={busy}><Settings size={19} /></Button></DropdownMenuTrigger>
      <DropdownMenuContent align="end"><DropdownMenuItem onSelect={() => setDialog({ type: "password" })}>Đổi mật khẩu</DropdownMenuItem><DropdownMenuItem onSelect={logout}><LogOut size={15} /> Đăng xuất</DropdownMenuItem><DropdownMenuSeparator /><DropdownMenuItem variant="destructive" onSelect={() => setDialog({ type: "account" })}>Xóa tài khoản</DropdownMenuItem></DropdownMenuContent></DropdownMenu></header>
    <section className="profile-summary">
      <button className="profile-avatar-button" onClick={() => setDialog({ type: "avatar" })} aria-label="Đổi ảnh đại diện"><img src={imageUrl(profile.userAvatarUrl)} alt="Ảnh đại diện của bạn" /><span>Sửa ảnh</span></button>
      <div className="profile-identity"><p className="profile-eyebrow">GÓC CỦA BẠN</p><h2>{profile.fullname}</h2><p className="profile-handle">@{profile.username}</p>
        {profile.dob && <p className="profile-birthday">Sinh ngày {profile.dob.split("-").reverse().join("/")} <LockKeyhole size={12} aria-label="Chỉ mình tôi" /></p>}
      </div>
      <div className="profile-summary-actions"><Button variant="outline" onClick={() => setDialog({ type: "profile" })}>Sửa hồ sơ</Button><Button variant="ghost" asChild><Link to={`/user/${id}`}>Xem công khai <ArrowUpRight size={14} /></Link></Button></div>
    </section>
    <nav className="profile-tabs" aria-label="Nội dung trang cá nhân"><button aria-pressed={!sharedTab} onClick={() => setTab("posts")}>Bài đăng <span>{posts.length}</span></button><button aria-pressed={sharedTab} onClick={() => setTab("shares")}>Đã chia sẻ <span>{shares.length}</span></button><Link to="/post" className="profile-create" aria-label="Viết bài mới"><Plus size={19} /></Link></nav>
    <section className="profile-posts" aria-label={sharedTab ? "Bài đã chia sẻ" : "Bài đăng của bạn"} aria-busy={loading}>
      {(errors[tab] || errors.profile) && <div className="profile-inline-error" role="alert"><p>{errors[tab] || errors.profile}</p><Button variant="outline" onClick={() => load()} disabled={loading}>Tải lại</Button></div>}
      {loading && <p role="status" className="profile-loading">Đang tải…</p>}
      {!items.length && !loading && !errors[tab] && <div className="profile-empty"><h3>{sharedTab ? "Chưa có bài chia sẻ" : "Câu chuyện của bạn bắt đầu ở đây"}</h3><p>{sharedTab ? "Bài viết bạn chia sẻ sẽ được lưu tại đây." : "Chia sẻ một điều bạn nghĩ, hoặc giữ lại cho riêng mình."}</p><Button variant="outline" asChild><Link to={sharedTab ? "/" : "/post"}>{sharedTab ? "Khám phá bảng tin" : "Viết bài đầu tiên"}</Link></Button></div>}
      {items.map((post) => {
        const postId = sharedTab ? post.blogId : post.id;
        return <article className="profile-post" key={postId}>
          {sharedTab && <p className="profile-share-label">Bạn đã chia sẻ · <Timestamp value={post.sharedAt} /></p>}
          <div className="profile-post-heading"><Link to={`/user/${sharedTab ? post.originalUserId : id}`} className="profile-post-author"><img src={imageUrl(post.userAvatarUrl)} alt="" /><span><strong>@{post.username}</strong><span className="profile-post-meta"><Timestamp value={sharedTab ? post.originalCreatedAt : post.createdAt} />{post.published ? <><Globe size={12} /> Công khai</> : <><LockKeyhole size={12} /> Chỉ mình tôi</>}</span></span></Link>
            <DropdownMenu><DropdownMenuTrigger asChild><Button variant="ghost" size="icon" aria-label="Tùy chọn bài viết" disabled={busy}><MoreHorizontal size={20} /></Button></DropdownMenuTrigger><DropdownMenuContent align="end">{sharedTab ? <DropdownMenuItem onSelect={() => setDialog({ type: "unshare", post })}>Bỏ chia sẻ</DropdownMenuItem> : <><DropdownMenuItem onSelect={() => setDialog({ type: "post", post })}>Chỉnh sửa bài viết</DropdownMenuItem><DropdownMenuItem onSelect={() => access(post)}>{post.published ? "Chuyển sang chỉ mình tôi" : "Chuyển sang công khai"}</DropdownMenuItem><DropdownMenuSeparator /><DropdownMenuItem variant="destructive" onSelect={() => setDialog({ type: "delete", post })}>Xóa bài viết</DropdownMenuItem></>}</DropdownMenuContent></DropdownMenu>
          </div>
          <Link to={`/post/${postId}`} className="profile-post-body">{post.content && <p>{post.content}</p>}{post.imageContentUrls?.length > 0 && <div className="profile-post-images">{post.imageContentUrls.map((url, index) => <img src={imageUrl(url)} alt={`Ảnh bài viết ${index + 1}`} key={url} loading="lazy" />)}</div>}</Link>
          <Link to={`/post/${postId}`} className="profile-post-footer"><span>{post.likesCount || 0} lượt thích · {post.commentsCount || 0} bình luận · {post.sharesCount || 0} chia sẻ</span><ArrowUpRight size={15} aria-hidden="true" /></Link>
        </article>;
      })}
    </section>
    {(dialog?.type === "profile" || dialog?.type === "password") && <Edit key={dialog.type} mode={dialog.type} user={profile} onClose={closeDialog} onSave={savedProfile} />}
    {dialog?.type === "avatar" && <EditAvt userId={id} currentAvatar={imageUrl(profile.userAvatarUrl)} onClose={closeDialog} onSave={savedProfile} />}
    {dialog?.type === "post" && <PostEditor post={dialog.post} onClose={closeDialog} onSave={savedPost} />}
    {["account", "delete", "unshare"].includes(dialog?.type) && <AlertDialog open onOpenChange={(open) => { if (!open) closeDialog(); }}><AlertDialogContent className="profile-dialog">
      <AlertDialogHeader><AlertDialogTitle>{dialog.type === "account" ? "Xóa tài khoản?" : dialog.type === "delete" ? "Xóa bài viết?" : "Bỏ chia sẻ bài viết?"}</AlertDialogTitle><AlertDialogDescription>{dialog.type === "account" ? "Tài khoản, bài viết và lượt tương tác của bạn sẽ bị xóa. Bình luận của bạn trong bài người khác sẽ trở thành bình luận đã xóa để giữ các câu trả lời. Thao tác này không thể hoàn tác." : dialog.type === "delete" ? "Bài viết, bình luận và các tương tác liên quan sẽ bị xóa. Thao tác này không thể hoàn tác." : "Bài viết gốc vẫn còn. Chỉ lượt chia sẻ của bạn được gỡ."}</AlertDialogDescription></AlertDialogHeader>
      <form className="profile-form" onSubmit={confirm} aria-busy={busy}>{dialog.type === "account" && <label>Mật khẩu hiện tại<input autoFocus type="password" autoComplete="current-password" required value={currentPassword} onChange={(event) => setCurrentPassword(event.target.value)} disabled={busy} /></label>}{actionError && <p role="alert" className="profile-form-error">{actionError}</p>}<AlertDialogFooter><Button type="button" variant="outline" disabled={busy} onClick={closeDialog}>Hủy</Button><Button type="submit" className="profile-danger" disabled={busy}>{busy ? "Đang xử lý…" : dialog.type === "unshare" ? "Bỏ chia sẻ" : "Xác nhận xóa"}</Button></AlertDialogFooter></form>
    </AlertDialogContent></AlertDialog>}
  </main>;
}

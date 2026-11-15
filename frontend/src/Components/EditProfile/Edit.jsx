import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { api, apiErrorMessage } from "../../config/api";
import { clearSession } from "../../config/auth";
import { toast } from "sonner";
import { Button } from "../ui/button";
import { AlertDialog, AlertDialogContent, AlertDialogHeader, AlertDialogTitle, AlertDialogDescription, AlertDialogFooter } from "../ui/alert-dialog";
import "./Edit.css";

const MIN_PASSWORD_LENGTH = 4;
const MAX_PASSWORD_LENGTH = 128;

export default function Edit({ user, mode = "profile", onClose, onSave }) {
  const passwordMode = mode === "password";
  const [username, setUsername] = useState(user.username);
  const [fullname, setFullname] = useState(user.fullname);
  const [dob, setDob] = useState(user.dob || "");
  const [currentPassword, setCurrentPassword] = useState("");
  const [password, setPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const navigate = useNavigate();

  async function submit(event) {
    event.preventDefault();
    if (busy) return;
    setError("");
    if (passwordMode && (password !== confirmation || Array.from(password).length < MIN_PASSWORD_LENGTH || Array.from(password).length > MAX_PASSWORD_LENGTH)) {
      setError("Mật khẩu mới cần từ 4–128 ký tự và khớp phần nhập lại.");
      return;
    }
    setBusy(true);
    try {
      if (passwordMode) {
        await api.put(`/api/v1/users/${user.id}`, { currentPassword, password });
        clearSession();
        toast.success("Đã đổi mật khẩu. Hãy đăng nhập lại.");
        navigate("/signin", { replace: true });
      } else {
        const { data } = await api.patch(`/api/v1/users/${user.id}`, { username, fullname, dob: dob || null });
        onSave(data);
        toast.success("Đã lưu hồ sơ.");
      }
      onClose();
    } catch (failure) {
      setError(apiErrorMessage(failure));
    } finally {
      setBusy(false);
    }
  }

  return <AlertDialog open onOpenChange={(open) => { if (!open && !busy) onClose(); }}>
    <AlertDialogContent className="profile-dialog">
      <AlertDialogHeader>
        <AlertDialogTitle>{passwordMode ? "Đổi mật khẩu" : "Chỉnh sửa hồ sơ"}</AlertDialogTitle>
        <AlertDialogDescription>{passwordMode ? "Sau khi đổi, tất cả phiên đăng nhập sẽ kết thúc." : "Ngày sinh chỉ hiển thị với bạn. Tên đăng nhập cũng được dùng để đăng nhập."}</AlertDialogDescription>
      </AlertDialogHeader>
      <form onSubmit={submit} className="profile-form" aria-busy={busy}>
        {passwordMode ? <>
          <label>Mật khẩu hiện tại<input autoFocus type="password" autoComplete="current-password" required value={currentPassword} onChange={(event) => setCurrentPassword(event.target.value)} disabled={busy} /></label>
          <label>Mật khẩu mới<input type="password" autoComplete="new-password" required value={password} onChange={(event) => setPassword(event.target.value)} disabled={busy} /></label>
          <label>Nhập lại mật khẩu mới<input type="password" autoComplete="new-password" required value={confirmation} onChange={(event) => setConfirmation(event.target.value)} disabled={busy} /></label>
        </> : <>
          <label>Tên đăng nhập<input autoFocus autoComplete="username" required value={username} onChange={(event) => setUsername(event.target.value)} disabled={busy} /></label>
          <label>Họ tên<input autoComplete="name" required value={fullname} onChange={(event) => setFullname(event.target.value)} disabled={busy} /></label>
          <label>Ngày sinh<input type="date" autoComplete="bday" value={dob} onChange={(event) => setDob(event.target.value)} disabled={busy} /></label>
        </>}
        {error && <p role="alert" className="profile-form-error">{error}</p>}
        <AlertDialogFooter>
          <Button type="button" variant="outline" onClick={onClose} disabled={busy}>Hủy</Button>
          <Button type="submit" disabled={busy}>{busy ? "Đang lưu…" : "Lưu thay đổi"}</Button>
        </AlertDialogFooter>
      </form>
    </AlertDialogContent>
  </AlertDialog>;
}

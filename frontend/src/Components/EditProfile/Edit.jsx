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
    if (passwordMode && !password.trim()) {
      setError("Password must not contain only spaces.");
      return;
    }
    if (passwordMode && (password !== confirmation || Array.from(password).length < MIN_PASSWORD_LENGTH || Array.from(password).length > MAX_PASSWORD_LENGTH)) {
      setError("Your new password must be 4–128 characters and match the confirmation.");
      return;
    }
    setBusy(true);
    try {
      if (passwordMode) {
        await api.put(`/api/v1/users/${user.id}`, { currentPassword, password });
        clearSession();
        toast.success("Password changed. Please sign in again.");
        navigate("/signin", { replace: true });
      } else {
        const { data } = await api.patch(`/api/v1/users/${user.id}`, { username, fullname, dob: dob || null });
        onSave(data);
        toast.success("Profile saved.");
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
        <AlertDialogTitle>{passwordMode ? "Change password" : "Edit profile"}</AlertDialogTitle>
        <AlertDialogDescription>{passwordMode ? "Changing your password will end all active sessions." : "Only you can see your date of birth. Your username is also used to sign in."}</AlertDialogDescription>
      </AlertDialogHeader>
      <form onSubmit={submit} className="profile-form" aria-busy={busy}>
        {passwordMode ? <>
          <label>Current password<input autoFocus type="password" autoComplete="current-password" required value={currentPassword} onChange={(event) => setCurrentPassword(event.target.value)} disabled={busy} /></label>
          <label>New password<input type="password" autoComplete="new-password" required value={password} onChange={(event) => setPassword(event.target.value)} disabled={busy} /></label>
          <label>Confirm new password<input type="password" autoComplete="new-password" required value={confirmation} onChange={(event) => setConfirmation(event.target.value)} disabled={busy} /></label>
        </> : <>
          <label>Username<input autoFocus autoComplete="username" required value={username} onChange={(event) => setUsername(event.target.value)} disabled={busy} /></label>
          <label>Full name<input autoComplete="name" required value={fullname} onChange={(event) => setFullname(event.target.value)} disabled={busy} /></label>
          <label>Date of birth<input type="date" autoComplete="bday" value={dob} onChange={(event) => setDob(event.target.value)} disabled={busy} /></label>
        </>}
        {error && <p role="alert" className="profile-form-error">{error}</p>}
        <AlertDialogFooter>
          <Button type="button" variant="outline" onClick={onClose} disabled={busy}>Cancel</Button>
          <Button type="submit" disabled={busy}>{busy ? "Saving…" : "Save changes"}</Button>
        </AlertDialogFooter>
      </form>
    </AlertDialogContent>
  </AlertDialog>;
}

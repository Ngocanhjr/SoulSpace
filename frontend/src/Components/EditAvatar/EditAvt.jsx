import { useEffect, useState } from "react";
import { api, apiErrorMessage } from "../../config/api";
import { DEFAULT_AVATAR_URL } from "../../config/assets";
import { toast } from "sonner";
import { Button } from "../ui/button";
import { AlertDialog, AlertDialogContent, AlertDialogHeader, AlertDialogTitle, AlertDialogDescription, AlertDialogFooter } from "../ui/alert-dialog";
import "../EditProfile/Edit.css";
import "./EditAvt.css";

const MAX_IMAGE_BYTES = 10 * 1024 * 1024;

export default function EditAvt({ userId, currentAvatar, onClose, onSave }) {
  const [file, setFile] = useState(null);
  const [preview, setPreview] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  useEffect(() => {
    if (!file) return;
    const url = URL.createObjectURL(file);
    setPreview(url);
    return () => URL.revokeObjectURL(url);
  }, [file]);

  function select(event) {
    const next = event.target.files[0];
    event.target.value = "";
    if (!next) return;
    if (!["image/jpeg", "image/png"].includes(next.type) || next.size > MAX_IMAGE_BYTES) {
      setError("Choose a JPEG or PNG image up to 10 MiB.");
      return;
    }
    setError("");
    setFile(next);
  }

  async function save(remove = false) {
    if (busy || (!remove && !file)) return;
    setBusy(true);
    setError("");
    try {
      let data;
      if (remove) {
        await api.delete(`/api/v1/users/${userId}/avatar`);
        ({ data } = await api.get(`/api/v1/users/patch/${userId}`));
      } else {
        const body = new FormData();
        body.append("file", file);
        ({ data } = await api.patch(`/api/v1/users/${userId}/avatar`, body));
      }
      onSave(data);
      toast.success(remove ? "Default avatar restored." : "Avatar updated.");
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
        <AlertDialogTitle>Avatar</AlertDialogTitle>
        <AlertDialogDescription>This image will be public. JPEG or PNG, up to 10 MiB and 25 million pixels.</AlertDialogDescription>
      </AlertDialogHeader>
      <div className="profile-form" aria-busy={busy}>
        <img className="profile-avatar-preview" src={(file && preview) || currentAvatar || DEFAULT_AVATAR_URL} alt="Avatar preview" />
        <label>Choose a new image<input type="file" accept="image/jpeg,image/png" onChange={select} disabled={busy} /></label>
        {error && <p role="alert" className="profile-form-error">{error}</p>}
        <Button variant="outline" disabled={busy || currentAvatar === DEFAULT_AVATAR_URL} onClick={() => save(true)}>Use default avatar</Button>
        <AlertDialogFooter>
          <Button variant="outline" disabled={busy} onClick={onClose}>Cancel</Button>
          <Button disabled={busy || !file} onClick={() => save()}>{busy ? "Saving…" : "Save image"}</Button>
        </AlertDialogFooter>
      </div>
    </AlertDialogContent>
  </AlertDialog>;
}

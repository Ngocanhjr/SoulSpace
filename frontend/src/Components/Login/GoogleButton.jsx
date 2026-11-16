import { useState } from "react";
import { toast } from "sonner";
import { startGoogle } from "../../config/google";
import { apiErrorMessage } from "../../config/api";
import { assetUrl } from "../../config/assets";
import { Button } from "../ui/button";
import "./GoogleButton.css";

export default function GoogleButton({ disabled = false }) {
  const [busy, setBusy] = useState(false);
  async function start() {
    if (busy) return;
    setBusy(true);
    try { await startGoogle(); }
    catch (failure) {
      toast.error(apiErrorMessage(failure, "Google sign-in is unavailable. Please try again."));
      setBusy(false);
    }
  }
  return <Button type="button" variant="outline" className="google-button" disabled={disabled || busy} onClick={start}>
    <img src={assetUrl("google.png")} alt="" width="18" height="18" className="google-button-icon" aria-hidden="true" />
    {busy ? "Connecting to Google…" : "Continue with Google"}
  </Button>;
}

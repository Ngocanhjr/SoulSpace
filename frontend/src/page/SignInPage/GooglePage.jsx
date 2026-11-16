import { useEffect, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { api, apiErrorMessage } from "../../config/api";
import { clearSession, useAuth } from "../../config/auth";
import { refreshGoogleSession, takeGoogleAction } from "../../config/google";
import { Button } from "../../Components/ui/button";
import SignUp from "../SignUpPage/SignUp";
import "./SignIn.css";

const MIN_PASSWORD_LENGTH = 4;
const MAX_PASSWORD_LENGTH = 128;

export default function GooglePage() {
  const { step } = useParams();
  const navigate = useNavigate();
  const { user: sessionUser } = useAuth();
  const started = useRef(false);
  const [phase, setPhase] = useState("loading");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [pendingProfile, setPendingProfile] = useState(null);
  const [password, setPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [verification, setVerification] = useState(null);

  useEffect(() => {
    if (started.current) return;
    started.current = true;
    async function finish() {
      if (step === "complete") {
        takeGoogleAction();
        const { data } = await api.get("/api/v1/auth/google/onboarding");
        setPendingProfile(data);
        setPhase("onboarding");
        return;
      }
      const action = takeGoogleAction();
      if (step !== "result") throw new Error("Google sign-in could not be completed.");
      const user = await refreshGoogleSession();
      if (!user) throw new Error("Please sign in again.");
      if (["SET_PASSWORD", "DELETE_ACCOUNT"].includes(action)) {
        const { data } = await api.post("/api/v1/auth/reauthenticate", { intent: action });
        setVerification({ action, userId: user.id, proof: data.reauthProof });
        setPhase("confirm");
      } else navigate("/user", { replace: true });
    }
    finish().catch((failure) => {
      setError(apiErrorMessage(failure, "Google sign-in could not be completed. Please try again."));
      setPhase("error");
    });
  }, [navigate, step]);

  async function submit(event) {
    event.preventDefault();
    if (busy) return;
    setError("");
    if (verification?.action === "SET_PASSWORD" && !password.trim()) {
      setError("Password must not contain only spaces.");
      return;
    }
    if (verification?.action === "SET_PASSWORD" && (password !== confirmation || Array.from(password).length < MIN_PASSWORD_LENGTH || Array.from(password).length > MAX_PASSWORD_LENGTH)) {
      setError("Use 4–128 characters and make sure both passwords match.");
      return;
    }
    setBusy(true);
    try {
        if (verification.action === "DELETE_ACCOUNT") {
          await api.delete(`/api/v1/users/${verification.userId}`, { data: { reauthProof: verification.proof } });
        } else await api.post("/api/v1/auth/password", { password, reauthProof: verification.proof });
        setVerification(null);
        setPassword("");
        setConfirmation("");
        clearSession();
        navigate("/signin", { replace: true });
    } catch (failure) { setError(apiErrorMessage(failure)); }
    finally { setBusy(false); }
  }

  if (phase === "onboarding") return <SignUp googleProfile={pendingProfile} />;

  const deleting = verification?.action === "DELETE_ACCOUNT";
  return <div className="signin-container"><section className="signin-box" aria-busy={busy}>
    <h2 className="signin-title">{phase === "confirm" ? deleting ? "Delete account?" : "Set a password" : "Google sign-in"}</h2>
    {phase === "loading" && <p role="status">Completing Google sign-in…</p>}
    {error && <p role="alert">{error}</p>}
    {phase === "confirm" && <form className="profile-form" onSubmit={submit}>
      {deleting ? <p>Your account, posts, and interactions will be deleted. Comments on other posts will become deleted placeholders to preserve replies. This cannot be undone.</p> : <>
        <p>Setting a password will end all active sessions.</p>
        <label>New password<input required type="password" autoComplete="new-password" value={password} onChange={(event) => setPassword(event.target.value)} disabled={busy} /></label>
        <label>Confirm password<input required type="password" autoComplete="new-password" value={confirmation} onChange={(event) => setConfirmation(event.target.value)} disabled={busy} /></label>
      </>}
      <Button type="submit" disabled={busy}>{busy ? "Saving…" : deleting ? "Delete account" : "Set password"}</Button>
    </form>}
    <Button variant="ghost" disabled={busy} onClick={() => navigate(sessionUser ? "/user" : "/signin")}>
      {phase === "confirm" ? "Cancel" : sessionUser ? "Back to profile" : "Back to sign in"}
    </Button>
  </section></div>;
}

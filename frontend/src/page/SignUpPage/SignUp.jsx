import { useState } from "react";
import GoogleButton from "../../Components/Login/GoogleButton";
import "./SignUp.css";
import { api, apiErrorMessage } from "../../config/api";
import { refreshGoogleSession } from "../../config/google";
import { Link, useNavigate } from "react-router-dom";
import { toast } from "sonner";
import { FaEye, FaEyeSlash } from "react-icons/fa";

export default function SignUp({ googleProfile = null }) {
  const isGoogle = googleProfile !== null;
  const [fullname, setFullName] = useState(googleProfile?.suggestedFullname || "");
  const [username, setUserName] = useState("");
  const [password, setPassword] = useState("");
  const [birthday, setBirthday] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [isSubmit, setIsSubmit] = useState(false); // để tránh click đăng ký nhiều lần
  const navigate = useNavigate(); // dùng để chuyển page sau khi đky thành công
  const today = new Date().toISOString().split("T")[0];

  const handleSubmit = async (e) => {
    e.preventDefault(); // tránh reload page khi submit form

    // nếu đang gửi request thì bỏ qua
    if (isSubmit) return;
    if (!isGoogle && !password.trim()) {
      toast.error("Password must not contain only spaces.");
      return;
    }

    setIsSubmit(true); // chặn click nút lần 2

    const formData = { fullname, username, dob: birthday || null };
    if (!isGoogle) formData.password = password;

    try {
      await api.post(isGoogle ? "/api/v1/auth/google/onboarding" : "/api/v1/users/sign-up", formData);
      setPassword("");
      if (isGoogle) {
        await refreshGoogleSession();
        navigate("/user", { replace: true });
      } else {
        toast.success("Sign up successfully! Please sign in.");
        navigate("/signin");
      }
    } catch (error) {
      toast.error(apiErrorMessage(error));
    } finally {
      setIsSubmit(false);
    }
  };

  return (
    <div className="signup-container">
      <form className="signup-form" onSubmit={handleSubmit}>
        <h2 className="signup-title">Create Account</h2>

        <label>Full Name</label>
        <input
          type="text"
          placeholder="Enter full name"
          autoComplete="name"
          value={fullname}
          onChange={(e) => setFullName(e.target.value)}
          required
        />

        <label>Username</label>
        <input
          type="text"
          placeholder="Enter username"
          autoComplete="username"
          value={username}
          onChange={(e) => setUserName(e.target.value)}
          required
        />

        <label>Birthday</label>
        <input
          type="date"
          value={birthday}
          max={today}
          onChange={(e) => setBirthday(e.target.value)}
        />

        {!isGoogle && <>
        <label>Password</label>
        <div className="pass-container">
          <input
            type={showPassword ? "text" : "password"}
            placeholder="Enter password"
            autoComplete="new-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            className="signup-input"
            required
          />

          <span
            className="eye-pass"
            onMouseDown={(e) => e.preventDefault()}
            onClick={() => setShowPassword(!showPassword)}
          >
            {showPassword ? <FaEyeSlash /> : <FaEye />}
          </span>
        </div>

        <small className="password-hint">
          4–128 characters. Spaces are allowed, but not on their own.
        </small>
        </>}

        <button type="submit" className="signup-btn" disabled={isSubmit}>
          {/* tránh click nhiều lần => gây lỗi */}
          {isSubmit ? "Creating account…" : "Sign Up"}
        </button>

        {!isGoogle && <GoogleButton disabled={isSubmit} />}
        <p className="signin-link">
          Already have an account? <Link to="/signin">Sign In</Link>
        </p>
      </form>
    </div>
  );
}

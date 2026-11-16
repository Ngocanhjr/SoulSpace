import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import "./SignIn.css";
import { apiErrorMessage } from "../../config/api";
import { signIn } from "../../config/auth";
import { toast } from "sonner";
import { FaEye, FaEyeSlash } from "react-icons/fa";
import GoogleButton from "../../Components/Login/GoogleButton";

const SignIn = () => {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const navigate = useNavigate();

  const handleSignIn = async (e) => {
    e.preventDefault(); //ngăn form reload page khi submit

    if (isSubmitting) return;
    setIsSubmitting(true);
    try {
      await signIn(username, password);
      setPassword("");
      toast.success("Sign in successfully!");
      navigate("/");
    } catch (error) {
      toast.error(apiErrorMessage(error));
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div className="signin-container">
      <div className="signin-box">
        <h2 className="signin-title">Sign In</h2>
        <form onSubmit={handleSignIn} className="signin-form">
          <input
            type="text"
            placeholder="Username"
            autoComplete="username"
            required
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            className="signin-input"
          />
          <div className="pass-container">
            <input
              type={showPassword ? "text" : "password"}
              placeholder="Password"
              autoComplete="current-password"
              required
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="signin-input"
            />

            <span
              className="toggle-password"
              onMouseDown={(e) => e.preventDefault()} // chặn khi click quá nhiều nó sẽ focus vào chỗ khác
              onClick={() => setShowPassword(!showPassword)}
            >
              {showPassword ? <FaEyeSlash /> : <FaEye />}
            </span>
          </div>

          <button
            type="submit"
            className="signin-button"
            disabled={isSubmitting}
          >
            {isSubmitting ? "Signing in…" : "Sign In"}
          </button>
        </form>
        <GoogleButton disabled={isSubmitting} />
        <a href="#" className="forgot-password">
          Forgot password?
        </a>
        <p className="signup-text">
          Don’t have an account?{" "}
          <Link to="/signup" className="signup-link">
            Sign Up
          </Link>
        </p>
      </div>
    </div>
  );
};

export default SignIn;

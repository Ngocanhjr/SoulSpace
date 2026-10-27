import './Login.css'
import { useNavigate } from 'react-router-dom';

function Login({ guest }) {
    const navigate = useNavigate();
    return (
        <div className="login-prompt">
            <span style={{fontSize: 25, fontWeight: "bold", marginBottom:"30px"}}>Sign in or Sign up</span>
            <p>Share what’s on your mind, connect with friends.</p>
            <button className="buttonLogin" onClick={() => navigate('/signin')}>
                Sign in
            </button>
            <button onClick={() => navigate("/signup")} className="buttonLogin">Sign up</button><br />
            <a onClick={() => guest(true)} href='#' >Continue as guest</a>
        </div>
    );
}
export default Login;

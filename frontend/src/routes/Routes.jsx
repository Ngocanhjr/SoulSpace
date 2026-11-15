import { Routes, Route, Navigate, useParams, useSearchParams } from "react-router-dom";
import { useAuth } from "../config/auth";
import HomePage from "../page/HomePage/HomePage.jsx";
import PostPage from "../page/PostPage/PostPage.jsx";
import UserPage from "../page/UserPage/UserPage.jsx";
import SearchPage from "../page/SearchPage/SearchPage.jsx";
import PublicProfilePage from "../page/PublicProfilePage/PublicProfilePage.jsx";
import SignIn from "../page/SignInPage/SignIn.jsx";
import SignUp from "../page/SignUpPage/SignUp.jsx";
import PostDetailPage from "../page/PostDetailPage/PostDetailPage.jsx";

function ProfileRoute() {
  const { userId } = useParams();
  const { user, ready } = useAuth();
  const [params] = useSearchParams();

  if (!ready) return <p role="status">Checking your session...</p>;
  if (user?.id === userId && params.get("preview") !== "public") {
    return <Navigate to="/user" replace />;
  }
  return <PublicProfilePage />;
}

export default function Router() {
  return (
    <Routes>
      <Route path="/" element={<HomePage />} />
      <Route path="/post" element={<PostPage />} />
      <Route path="/post/:postId" element={<PostDetailPage />} />
      <Route path="/search" element={<SearchPage />} />
      <Route path="/search/user/:userId" element={<ProfileRoute />} />
      <Route path="/search/post/:postId" element={<PostDetailPage />} />
      <Route path="/user" element={<UserPage />} />
      <Route path="/user/:userId" element={<ProfileRoute />} />
      <Route path="/signin" element={<SignIn />} />
      <Route path="/signup" element={<SignUp />} />
      <Route path="/home" element={<HomePage />} />
    </Routes>
  );
}

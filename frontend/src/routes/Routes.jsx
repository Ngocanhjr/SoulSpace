import { Routes, Route } from "react-router-dom";
import HomePage from "../page/HomePage/HomePage.jsx";
import PostPage from "../page/PostPage/PostPage.jsx";
import UserPage from "../page/UserPage/UserPage.jsx";
import SearchPage from "../page/SearchPage/SearchPage.jsx";
import PublicProfilePage from "../page/PublicProfilePage/PublicProfilePage.jsx";
import SignIn from "../page/SignInPage/SignIn.jsx";
import SignUp from "../page/SignUpPage/SignUp.jsx";
import PostDetailPage from "../page/PostDetailPage/PostDetailPage.jsx";

export default function Router() {
  return (
    <Routes>
      <Route path="/" element={<HomePage />} />
      <Route path="/post" element={<PostPage />} />
      <Route path="/post/:postId" element={<PostDetailPage />} />
      <Route path="/search" element={<SearchPage />} />
      <Route path="/search/user/:userId" element={<PublicProfilePage />} />
      <Route path="/search/post/:postId" element={<PostDetailPage />} />
      <Route path="/user" element={<UserPage />} />
      <Route path="/user/:userId" element={<PublicProfilePage />} />
      <Route path="/signin" element={<SignIn />} />
      <Route path="/signup" element={<SignUp />} />
      <Route path="/home" element={<HomePage />} />
    </Routes>
  );
}

import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import axios from "axios";
import { api, buildApiUrl } from "../../config/api";
import { Search, X } from "lucide-react";
import style from "./SearchPage.module.css";
import { DEFAULT_AVATAR_URL } from "../../config/assets";
import { useAuth } from "../../config/auth";
import {
  readSearchHistory,
  writeSearchHistory,
} from "../../config/searchHistory.js";

const PEOPLE_SUGGESTION_LIMIT = 4;
const POST_SUGGESTION_LIMIT = 8;
const POST_RESULT_LIMIT = 50;

function normalizeText(value) {
  return String(value || "").trim().toLowerCase();
}

function getPostPreview(content) {
  const safeContent = String(content || "").trim();
  if (safeContent.length <= 120) {
    return safeContent;
  }
  return `${safeContent.slice(0, 120)}...`;
}

async function searchPeople(query, signal) {
  const { data } = await api.get("/api/v1/users", { params: { q: query }, signal });
  if (!Array.isArray(data)) throw new Error("Invalid users response");
  return data;
}

async function searchPosts(query, limit, signal) {
  const { data } = await api.get("/api/v1/search", { params: { q: query, limit }, signal });
  if (!Array.isArray(data)) throw new Error("Invalid search response");
  return data;
}

function postSearchError(error) {
  const status = error.response?.status;
  return status === 401 ? "Bạn cần đăng nhập để tìm bài viết." :
    status === 400 ? "Câu tìm kiếm cần có từ 1 đến 2000 ký tự." :
      status === 503 ? "Dịch vụ tìm kiếm đang bận. Hãy thử lại." :
        "Không thể tìm bài viết lúc này.";
}

export default function SearchPage() {
  const navigate = useNavigate();
  const [suggestions, setSuggestions] = useState({ query: "", users: [], error: "" });
  const [searchedPeopleResults, setSearchedPeopleResults] = useState([]);
  const [peopleSearching, setPeopleSearching] = useState(false);
  const [postSuggestions, setPostSuggestions] = useState({
    query: "", posts: [], error: "", loading: false,
  });
  const [posts, setPosts] = useState([]);
  const [query, setQuery] = useState("");
  const [submittedQuery, setSubmittedQuery] = useState("");
  const [searching, setSearching] = useState(false);
  const [searchError, setSearchError] = useState("");
  const [peopleError, setPeopleError] = useState("");
  const [needsLogin, setNeedsLogin] = useState(false);
  const activeRequest = useRef(null);
  const [isInputFocused, setIsInputFocused] = useState(false);
  const normalizedQuery = normalizeText(query);
  const { user, ready } = useAuth();
  const historyOwner = user?.id || "guest";

  const [historyByUser, setHistoryByUser] = useState({});

  const searchHistory = ready
    ? (historyByUser[historyOwner] ?? readSearchHistory(historyOwner))
    : [];

  const updateSearchHistory = (items) => {
    if (!ready) return;

    const next = writeSearchHistory(historyOwner, items);
    setHistoryByUser((current) => ({
      ...current,
      [historyOwner]: next,
    }));
  };

  useEffect(() => {
    if (!normalizedQuery) return;
    const request = new AbortController();
    const timeout = setTimeout(async () => {
      try {
        const users = await searchPeople(normalizedQuery, request.signal);
        if (!request.signal.aborted) {
          setSuggestions({ query: normalizedQuery, users, error: "" });
        }
      } catch (error) {
        if (!request.signal.aborted && !axios.isCancel(error)) {
          setSuggestions({
            query: normalizedQuery,
            users: [],
            error: "Chưa tìm được người dùng. Hãy thử lại.",
          });
        }
      }
    }, 300);
    return () => {
      clearTimeout(timeout);
      request.abort();
    };
  }, [normalizedQuery]);

  useEffect(() => {
    if (!normalizedQuery || !isInputFocused) return;
    const request = new AbortController();
    const timeout = setTimeout(async () => {
      setPostSuggestions({ query: normalizedQuery, posts: [], error: "", loading: true });
      try {
        const results = await searchPosts(normalizedQuery, POST_SUGGESTION_LIMIT, request.signal);
        if (!request.signal.aborted) {
          setPostSuggestions({ query: normalizedQuery, posts: results, error: "", loading: false });
        }
      } catch (error) {
        if (!request.signal.aborted && !axios.isCancel(error)) {
          setPostSuggestions({
            query: normalizedQuery, posts: [], error: postSearchError(error), loading: false,
          });
        }
      }
    }, 600);
    return () => {
      clearTimeout(timeout);
      request.abort();
    };
  }, [normalizedQuery, isInputFocused]);

  useEffect(() => () => activeRequest.current?.abort(), []);

  const peopleSuggestions = suggestions.query === normalizedQuery
    ? suggestions.users.slice(0, PEOPLE_SUGGESTION_LIMIT)
    : [];
  const suggestionError = suggestions.query === normalizedQuery ? suggestions.error : "";
  const suggestedPosts = postSuggestions.query === normalizedQuery ? postSuggestions.posts : [];
  const postSuggestionError = postSuggestions.query === normalizedQuery ? postSuggestions.error : "";
  const postSuggestionsLoading = postSuggestions.query === normalizedQuery && postSuggestions.loading;

  const searchedPostResults = posts;

  const showSuggestions =
    !!normalizedQuery &&
    isInputFocused &&
    (peopleSuggestions.length > 0 || !!suggestionError || suggestedPosts.length > 0 ||
      !!postSuggestionError || postSuggestionsLoading);

  const runSearch = async (rawQuery) => {
    const safeQuery = rawQuery.trim();
    activeRequest.current?.abort();
    setSearchedPeopleResults([]);
    setPeopleError("");
    setPeopleSearching(false);
    setPosts([]);
    setSearchError("");
    setNeedsLogin(false);
    setSearching(false);
    setSubmittedQuery(safeQuery);
    setIsInputFocused(false);
    if (!safeQuery) {
      return;
    }
    updateSearchHistory([safeQuery, ...searchHistory]);
    const request = new AbortController();
    activeRequest.current = request;
    setPeopleSearching(true);
    searchPeople(safeQuery, request.signal)
      .then((users) => {
        if (!request.signal.aborted) setSearchedPeopleResults(users);
      })
      .catch((error) => {
        if (!request.signal.aborted && !axios.isCancel(error)) {
          setPeopleError("Không thể tìm người dùng lúc này. Hãy thử lại.");
        }
      })
      .finally(() => {
        if (activeRequest.current === request) setPeopleSearching(false);
      });
    setSearching(true);
    try {
      const results = await searchPosts(safeQuery, POST_RESULT_LIMIT, request.signal);
      if (request.signal.aborted) return;
      setPosts(results);
    } catch (error) {
      if (request.signal.aborted) return;
      const status = error.response?.status;
      setNeedsLogin(status === 401);
      setSearchError(postSearchError(error));
    } finally {
      if (activeRequest.current === request) setSearching(false);
    }
  };

  const handleSubmit = (event) => {
    event.preventDefault();
    void runSearch(query);
  };

  const handleClear = () => {
    activeRequest.current?.abort();
    activeRequest.current = null;
    setSearchedPeopleResults([]);
    setPeopleError("");
    setPeopleSearching(false);
    setSuggestions({ query: "", users: [], error: "" });
    setPostSuggestions({ query: "", posts: [], error: "", loading: false });
    setPosts([]);
    setSearching(false);
    setSearchError("");
    setNeedsLogin(false);
    setQuery("");
    setSubmittedQuery("");
    setIsInputFocused(false);
  };

  const handleOpenProfile = (userId, fromSuggestion = false) => {
    if (fromSuggestion) updateSearchHistory([query, ...searchHistory]);
    navigate(`/search/user/${userId}`);
  };

  const handleOpenPost = (postId, fromSuggestion = false) => {
    if (fromSuggestion) updateSearchHistory([query, ...searchHistory]);
    navigate(`/search/post/${postId}`);
  };

  return (
    <div className={style.searchPage}>
      <div className={style.header}>
        <h1>Search</h1>
      </div>

      <form className={style.searchForm} onSubmit={handleSubmit}>
        <div className={style.searchInputWrapper}>
          <Search size={18} className={style.searchIcon} />
          <input
            type="text"
            value={query}
            placeholder="Tìm kiếm"
            maxLength={2000}
            className={style.searchInput}
            onChange={(event) => setQuery(event.target.value)}
            onFocus={() => setIsInputFocused(true)}
          />
          {query && (
            <button
              type="button"
              className={style.clearButton}
              onClick={handleClear}
              aria-label="Clear search"
            >
              <X size={16} />
            </button>
          )}
        </div>
      </form>

      <div className={style.body}>
        {showSuggestions && (
          <div className={style.suggestionsPanel}>
            <SearchSection
              title="People"
              count={peopleSuggestions.length}
              emptyMessage={suggestionError || "No matching people suggestions."}
            >
              {peopleSuggestions.map((user) => (
                <button
                  type="button"
                  key={user.id}
                  className={style.resultItem}
                  onMouseDown={(event) => event.preventDefault()}
                  onClick={() => handleOpenProfile(user.id, true)}
                >
                  <img
                    src={user.userAvatarUrl || DEFAULT_AVATAR_URL}
                    alt={user.username || user.fullname || "User avatar"}
                    className={style.avatar}
                  />
                  <div className={style.resultTextGroup}>
                    <strong>{user.fullname || user.username || "Unknown user"}</strong>
                    <span>@{user.username || "unknown"}</span>
                  </div>
                </button>
              ))}
            </SearchSection>
            <SearchSection
              title="Posts"
              count={suggestedPosts.length}
              emptyMessage={postSuggestionsLoading
                ? "Đang tìm bài gần nghĩa..."
                : postSuggestionError || "Không có gợi ý bài viết phù hợp."}
            >
              {suggestedPosts.map((post) => (
                <PostResult key={post.id} post={post} onOpen={(postId) => handleOpenPost(postId, true)} />
              ))}
            </SearchSection>
          </div>
        )}

        {(!normalizedQuery || !submittedQuery) ? (
          !normalizedQuery && searchHistory.length > 0 ? (
            <section className={style.historyPanel}>
              <div className={style.historyHeader}>
                <h2>Tìm kiếm gần đây</h2>

                <button
                  type="button"
                  onClick={() => updateSearchHistory([])}
                >
                  Xóa tất cả
                </button>
              </div>

              <ul className={style.historyList}>
                {searchHistory.map((term) => (
                  <li key={term} className={style.historyRow}>
                    <button
                      type="button"
                      className={style.historyQuery}
                      title={term}
                      onClick={() => {
                        setQuery(term);
                        void runSearch(term);
                      }}
                    >
                      🕘 {term}
                    </button>

                    <button
                      type="button"
                      className={style.historyRemove}
                      aria-label={`Xóa tìm kiếm ${term}`}
                      onClick={() => {
                        updateSearchHistory(
                          searchHistory.filter((item) => item !== term)
                        );
                      }}
                    >
                      <X size={16} />
                    </button>
                  </li>
                ))}
              </ul>
            </section>
          ) : (
            <div className={style.emptyArea}>
              <p className={style.emptyTitle}>
                No search yet.
              </p>
              <span className={style.emptySubtitle}>
                Nhập tên người dùng hoặc câu tìm kiếm, rồi nhấn Enter.
              </span>
            </div>
          )
        ) : (
          <div className={style.resultsPanel}>
            <div className={style.resultSummary}>
              Results for <strong>"{submittedQuery}"</strong>
            </div>

            <SearchSection
              title="People"
              count={searchedPeopleResults.length}
              emptyMessage={peopleSearching
                ? "Đang tìm người dùng..."
                : peopleError || "Không tìm thấy người dùng phù hợp."}
            >
              {searchedPeopleResults.map((user) => (
                <button
                  type="button"
                  key={user.id}
                  className={style.resultItem}
                  onClick={() => handleOpenProfile(user.id)}
                >
                  <img
                    src={user.userAvatarUrl || DEFAULT_AVATAR_URL}
                    alt={user.username || user.fullname || "User avatar"}
                    className={style.avatar}
                  />
                  <div className={style.resultTextGroup}>
                    <strong>{user.fullname || user.username || "Unknown user"}</strong>
                    <span>@{user.username || "unknown"}</span>
                  </div>
                </button>
              ))}
            </SearchSection>

            {searching && <p role="status">Đang tìm bài gần nghĩa...</p>}
            {searchError && <p role="alert">{searchError}</p>}
            {needsLogin && <a href={buildApiUrl("/login")}>Đăng nhập backend</a>}
            {!searching && !searchError && (
              <SearchSection
                title="Posts"
                count={searchedPostResults.length}
                emptyMessage="No matching posts found."
              >
                {searchedPostResults.map((post) => (
                  <PostResult key={post.id} post={post} onOpen={handleOpenPost} />
                ))}
              </SearchSection>
            )}
          </div>
        )}
      </div>
    </div>
  );
}

function PostResult({ post, onOpen }) {
  return (
    <button
      type="button"
      className={style.resultItem}
      onMouseDown={(event) => event.preventDefault()}
      onClick={() => onOpen(post.id)}
    >
      <img
        src={post.imageContentUrls?.[0] || post.userAvatarUrl || DEFAULT_AVATAR_URL}
        alt={post.content || "Post preview"}
        className={style.previewImage}
      />
      <div className={style.resultTextGroup}>
        <strong>{post.username ? `@${post.username}` : "Unknown user"}</strong>
        <span>{getPostPreview(post.content)}</span>
      </div>
    </button>
  );
}

function SearchSection({ title, count, children, emptyMessage }) {
  const hasContent = Array.isArray(children)
    ? children.length > 0
    : Boolean(children);

  return (
    <section className={style.section}>
      <div className={style.sectionHeader}>
        <h2>{title}</h2>
        <span>{count}</span>
      </div>

      {hasContent ? children : <div className={style.emptySection}>{emptyMessage}</div>}
    </section>
  );
}

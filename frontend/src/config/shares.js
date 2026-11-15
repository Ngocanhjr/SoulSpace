import { api } from "./api.js";

export async function toggleShare(post, actorId, onChange) {
  const refresh = async () => {
    const { data } = await api.get("/api/v1/blogs/" + post.id);
    onChange({ sharedByMe: data.sharedByMe, sharesCount: data.sharesCount });
  };

  if (post.sharedByMe) {
    await api.delete("/api/v1/users/" + actorId + "/shared-blogs/" + post.id);
    onChange({ sharedByMe: false });
    try {
      await refresh();
      return { message: "Repost removed" };
    } catch (error) {
      if (error.response?.status === 404) return { message: "Repost removed", unavailable: true };
      return { message: "Repost removed, but the repost count could not be refreshed.", warning: true };
    }
  }

  try {
    const { data } = await api.post("/api/v1/blogs/" + post.id + "/share", {});
    onChange({ sharedByMe: true, sharesCount: data.sharesCount });
    return { message: "Reposted" };
  } catch (error) {
    if (error.response?.data?.code !== "ALREADY_SHARED") throw error;
    await refresh();
    return { message: "You have already reposted this post" };
  }
}

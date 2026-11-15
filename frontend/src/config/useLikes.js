import { useEffect, useMemo, useRef } from "react";
import { toast } from "sonner";
import { api, apiErrorMessage } from "./api.js";

export function createLikeQueue(save, change, error) {
  const entries = new Map();
  const observations = new Map();
  let revision = 0;
  let active = true;

  function display(path, entry) {
    if (!active) return;
    const next = {
      liked: entry.desired,
      likesCount: entry.count + Number(entry.desired) - Number(entry.confirmed),
    };
    observations.set(path, { revision: ++revision, next });
    change(path, next);
  }

  async function send(path, entry) {
    try {
      while (active && entry.desired !== entry.confirmed) {
        const desired = entry.desired;
        const revision = entry.revision;
        try {
          const result = await save(path, desired);
          if (result.liked !== desired || !Number.isInteger(result.likesCount) || result.likesCount < 0) {
            throw new Error("Invalid like response");
          }
          entry.confirmed = result.liked;
          entry.count = result.likesCount;
        } catch (failure) {
          if (!active) break;
          if (entry.revision === revision) entry.desired = entry.confirmed;
          error(failure);
        }
        display(path, entry);
      }
    } finally {
      entries.delete(path);
    }
  }

  return {
    toggle(path, liked, likesCount) {
      if (!active) return Promise.resolve();
      let entry = entries.get(path);
      if (!entry) {
        entry = { confirmed: liked, desired: liked, count: likesCount, revision: 0, task: null };
        entries.set(path, entry);
      }
      entry.desired = !entry.desired;
      entry.revision++;
      display(path, entry);
      if (!entry.task) entry.task = send(path, entry);
      return entry.task;
    },
    resume() { active = true; },
    stop() { active = false; },
    startRead() { return revision; },
    merge(path, value, readRevision) {
      const latest = observations.get(path);
      return latest && (latest.revision > readRevision || entries.has(path))
        ? { ...value, ...latest.next } : value;
    },
  };
}

export function useLikes(identity, onChange) {
  const callback = useRef(onChange);
  useEffect(() => { callback.current = onChange; }, [onChange]);
  const queue = useMemo(() => createLikeQueue(
    (path, liked) => identity
      ? api.post(path, { liked }).then(({ data }) => data)
      : Promise.reject(new Error("Authentication required")),
    (path, next) => callback.current(path, next),
    (error) => toast.error(apiErrorMessage(error, "Unable to update like.")),
  ), [identity]);
  useEffect(() => {
    queue.resume();
    return () => queue.stop();
  }, [queue]);
  return queue;
}

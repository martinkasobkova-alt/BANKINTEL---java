import React, { useEffect, useMemo, useState } from "react";
import { ChevronDown } from "lucide-react";

const selectClass =
  "w-full max-w-md h-10 pl-3 pr-8 rounded-xl border border-border bg-card text-sm text-foreground appearance-none";

const csCollator = new Intl.Collator("cs", { sensitivity: "base" });

function sortTopicsAlphabetically(topics) {
  return [...(topics || [])].sort((a, b) =>
    csCollator.compare(String(a?.label_cs || ""), String(b?.label_cs || ""))
  );
}

function normalizeTopicGroups(topicGroups, flatTopics, overviewGroups) {
  if (Array.isArray(topicGroups) && topicGroups.length) {
    return topicGroups
      .map((g) => ({
        id: g.id,
        label_cs: g.label_cs,
        topics: sortTopicsAlphabetically((g.topics || []).filter((t) => t?.id)),
      }))
      .filter((g) => g.topics.length);
  }
  const flat = Array.isArray(flatTopics) ? flatTopics : [];
  if (!flat.length) return [];
  const labels = new Map((overviewGroups || []).map((g) => [g.id, g.label_cs]));
  const order = (overviewGroups || []).map((g) => g.id);
  const buckets = new Map();
  for (const topic of flat) {
    const gid = String(topic.group_id || "ostatni").trim() || "ostatni";
    if (!buckets.has(gid)) buckets.set(gid, []);
    buckets.get(gid).push(topic);
  }
  const seen = new Set();
  const groups = [];
  for (const gid of order) {
    const topics = buckets.get(gid);
    if (!topics?.length) continue;
    seen.add(gid);
    groups.push({ id: gid, label_cs: labels.get(gid) || gid, topics: sortTopicsAlphabetically(topics) });
  }
  for (const [gid, topics] of buckets) {
    if (seen.has(gid) || !topics.length) continue;
    groups.push({ id: gid, label_cs: labels.get(gid) || gid, topics: sortTopicsAlphabetically(topics) });
  }
  return groups;
}

/** Jednoduchý dropdown témat seskupených podle kategorie. */
export function MacroTopicGroupedSelect({
  topicGroups,
  topics,
  overviewGroups,
  value = "",
  onChange,
  placeholder = "Vyberte téma…",
  disabled = false,
  className = "",
  id,
  grouped = true,
}) {
  const groups = useMemo(
    () => normalizeTopicGroups(topicGroups, topics, overviewGroups),
    [topicGroups, topics, overviewGroups]
  );
  const flatTopics = useMemo(
    () => groups.flatMap((g) => g.topics || []),
    [groups]
  );

  const renderOption = (topic) => (
    <option key={topic.id} value={topic.id}>
      {topic.label_cs}
      {topic.country_count != null
        ? ` · ${topic.country_count} oblastí`
        : topic.series_count != null
          ? ` (${topic.series_count})`
          : ""}
    </option>
  );

  const pickTopic = (topicId) => {
    if (!topicId) {
      onChange?.(null);
      return;
    }
    const found =
      flatTopics.find((t) => t.id === topicId) || { id: topicId, label_cs: topicId };
    onChange?.(found);
  };

  return (
    <div className="relative w-full max-w-md">
      <select
        id={id}
        className={`${className || selectClass} [&_optgroup]:font-semibold [&_optgroup]:text-foreground`}
        value={value}
        disabled={disabled || !flatTopics.length}
        onChange={(e) => pickTopic(e.target.value)}
      >
        <option value="">{placeholder}</option>
        {grouped
          ? groups.map((group) => (
              <optgroup key={group.id} label={group.label_cs}>
                {(group.topics || []).map(renderOption)}
              </optgroup>
            ))
          : flatTopics.map(renderOption)}
      </select>
      <ChevronDown
        className="pointer-events-none absolute right-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground"
        aria-hidden
      />
    </div>
  );
}

/** Kaskáda: nejdřív kategorie, pak konkrétní téma. */
export default function MacroTopicPicker({
  topicGroups,
  overviewGroups,
  onTopicSelect,
  disabled = false,
  groupLabel = "Kategorie",
  topicLabel = "Makro téma",
  groupPlaceholder = "Vyberte kategorii…",
  topicPlaceholder = "Vyberte téma…",
}) {
  const groups = useMemo(
    () => normalizeTopicGroups(topicGroups, null, overviewGroups),
    [topicGroups, overviewGroups]
  );

  const [groupId, setGroupId] = useState("");
  const [topicId, setTopicId] = useState("");

  useEffect(() => {
    setGroupId("");
    setTopicId("");
  }, [topicGroups]);

  const activeGroup = useMemo(
    () => groups.find((g) => g.id === groupId) || null,
    [groups, groupId]
  );

  const handleGroupChange = (nextGroupId) => {
    setGroupId(nextGroupId);
    setTopicId("");
  };

  const handleTopicChange = (nextTopicId) => {
    setTopicId(nextTopicId);
    if (!nextTopicId) return;
    const topic = activeGroup?.topics?.find((t) => t.id === nextTopicId);
    if (topic) onTopicSelect?.(topic);
  };

  return (
    <div className="grid gap-3 sm:grid-cols-2 max-w-3xl">
      <div className="space-y-1.5">
        <label htmlFor="macro-topic-group" className="text-xs font-medium text-muted-foreground">
          {groupLabel}
        </label>
        <select
          id="macro-topic-group"
          className={selectClass}
          value={groupId}
          disabled={disabled || !groups.length}
          onChange={(e) => handleGroupChange(e.target.value)}
        >
          <option value="">{groupPlaceholder}</option>
          {groups.map((group) => (
            <option key={group.id} value={group.id}>
              {group.label_cs} ({group.topics.length})
            </option>
          ))}
        </select>
      </div>
      <div className="space-y-1.5">
        <label htmlFor="macro-topic-item" className="text-xs font-medium text-muted-foreground">
          {topicLabel}
        </label>
        <select
          id="macro-topic-item"
          className={selectClass}
          value={topicId}
          disabled={disabled || !activeGroup}
          onChange={(e) => handleTopicChange(e.target.value)}
        >
          <option value="">{topicPlaceholder}</option>
          {(activeGroup?.topics || []).map((topic) => (
            <option key={topic.id} value={topic.id}>
              {topic.label_cs}
              {topic.country_count != null ? ` · ${topic.country_count} oblastí` : ""}
            </option>
          ))}
        </select>
      </div>
    </div>
  );
}

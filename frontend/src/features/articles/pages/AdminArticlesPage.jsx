import React, { useCallback, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import AppShell from "@/components/layout/AppShell";
import ArticleBodyView from "@/components/articles/ArticleBodyView";
import ArticleCoverImageField from "@/components/editor/ArticleCoverImageField";
import ArticleRichEditor from "@/components/editor/ArticleRichEditor";
import api, { formatApiErrorFromAxios } from "@/lib/api";
import { getArticleCoverImageUrl } from "@/lib/articleCover";
import { toast } from "sonner";

const emptyForm = () => ({
  title: "",
  summary: "",
  body: "",
  slug: "",
  cover_image_url: "",
  category_id: "",
  published: false,
});

function formatDate(iso) {
  if (!iso) return "—";
  try {
    return new Date(iso).toLocaleString("cs-CZ", {
      day: "numeric",
      month: "numeric",
      year: "numeric",
      hour: "2-digit",
      minute: "2-digit",
    });
  } catch {
    return iso;
  }
}

export default function AdminArticlesPage() {
  const { t } = useTranslation();
  const [rows, setRows] = useState([]);
  const [categories, setCategories] = useState([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState(null);
  const [editingId, setEditingId] = useState(null);
  const [form, setForm] = useState(emptyForm);
  const [saving, setSaving] = useState(false);
  const [newCategoryName, setNewCategoryName] = useState("");
  const [categorySaving, setCategorySaving] = useState(false);
  const [editingCategoryId, setEditingCategoryId] = useState(null);
  const [editingCategoryName, setEditingCategoryName] = useState("");

  const loadCategories = useCallback(async () => {
    try {
      const { data } = await api.get("/articles/categories");
      setCategories(Array.isArray(data) ? data : []);
    } catch {
      setCategories([]);
    }
  }, []);

  const load = useCallback(async () => {
    setLoading(true);
    setErr(null);
    try {
      const { data } = await api.get("/articles?limit=100");
      setRows(Array.isArray(data) ? data : []);
    } catch (e) {
      setErr(formatApiErrorFromAxios(e) || "Chyba načtení");
      setRows([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadCategories();
    load();
  }, [load, loadCategories]);

  const openNew = () => {
    setEditingId("new");
    setForm(emptyForm());
  };

  const openEdit = async (id) => {
    try {
      const { data } = await api.get(`/articles/${id}`);
      setEditingId(id);
      setForm({
        title: data.title || "",
        summary: data.summary || "",
        body: data.body || "",
        slug: data.slug || "",
        cover_image_url: data.cover_image_url || "",
        category_id: data.category_id || "",
        published: Boolean(data.published),
      });
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || "Chyba načtení");
    }
  };

  const closeForm = () => {
    setEditingId(null);
    setForm(emptyForm());
  };

  const save = async () => {
    const title = form.title.trim();
    const body = form.body.trim();
    if (!title || !body) {
      toast.error("Vyplňte název a text zprávy.");
      return;
    }
    setSaving(true);
    try {
      const cover = form.cover_image_url.trim();
      const categoryId = form.category_id.trim();
      const payload = {
        title,
        summary: form.summary.trim() || null,
        body,
        cover_image_url: cover || null,
        category_id: categoryId || null,
        published: form.published,
      };
      const slug = form.slug.trim();
      if (slug) payload.slug = slug;

      if (editingId === "new") {
        await api.post("/articles", payload);
        toast.success("Zpráva vytvořena");
      } else {
        await api.patch(`/articles/${editingId}`, payload);
        toast.success("Zpráva uložena");
      }
      closeForm();
      load();
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || "Chyba uložení");
    } finally {
      setSaving(false);
    }
  };

  const togglePublished = async (row) => {
    try {
      await api.patch(`/articles/${row.id}`, { published: !row.published });
      toast.success(row.published ? "Skryto" : "Publikováno");
      load();
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || "Chyba");
    }
  };

  const doDelete = async (id) => {
    if (!window.confirm("Opravdu smazat tuto zprávu?")) return;
    try {
      await api.delete(`/articles/${id}`);
      toast.success("Smazáno");
      if (editingId === id) closeForm();
      load();
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || "Chyba");
    }
  };

  const addCategory = async () => {
    const name = newCategoryName.trim();
    if (!name) {
      toast.error("Zadejte název podsekce.");
      return;
    }
    setCategorySaving(true);
    try {
      await api.post("/articles/categories", { name });
      setNewCategoryName("");
      toast.success("Podsekce vytvořena");
      await loadCategories();
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || "Chyba");
    } finally {
      setCategorySaving(false);
    }
  };

  const startEditCategory = (cat) => {
    setEditingCategoryId(cat.id);
    setEditingCategoryName(cat.name || "");
  };

  const saveCategory = async () => {
    const name = editingCategoryName.trim();
    if (!editingCategoryId || !name) return;
    setCategorySaving(true);
    try {
      await api.patch(`/articles/categories/${editingCategoryId}`, { name });
      setEditingCategoryId(null);
      setEditingCategoryName("");
      toast.success("Podsekce uložena");
      await loadCategories();
      load();
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || "Chyba");
    } finally {
      setCategorySaving(false);
    }
  };

  const deleteCategory = async (cat) => {
    if (!window.confirm(`Smazat podsekci „${cat.name}"? Zprávy v ní zůstanou, jen bez přiřazení.`)) return;
    setCategorySaving(true);
    try {
      await api.delete(`/articles/categories/${cat.id}`);
      toast.success("Podsekce smazána");
      if (form.category_id === cat.id) {
        setForm((f) => ({ ...f, category_id: "" }));
      }
      await loadCategories();
      load();
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || "Chyba");
    } finally {
      setCategorySaving(false);
    }
  };

  return (
    <AppShell title={t("pages.admin.adminArticlesTitle")} subtitle={t("pages.admin.adminArticlesSubtitle")}>
      <div className="bankoapp-white-panels-scope">
        <div className="mb-6 rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
          <h2 className="text-base font-bold text-slate-900">Zdroje zpráv</h2>
          <p className="mt-1 text-sm text-slate-600">
            Zprávy s příznakem „Koncept“ níže vznikají automaticky z RSS/Atom feedů, u kterých je zapnuté „do Zpráv“
            (např. tiskové zprávy ČNB, Reuters apod.) — cizojazyčné zdroje se navíc přeloží, pokud mají zapnuté „do
            CS“. Ostatní feedy (bez „do Zpráv“) slouží jen pro dashboard widget a sem nezasahují. Přidání a nastavení
            zdrojů je na samostatné stránce.
          </p>
          <a
            href="/admin/rss-feeds"
            className="mt-3 inline-flex items-center gap-1.5 rounded-lg bg-slate-900 px-4 py-2 text-sm font-semibold text-white hover:bg-slate-800"
          >
            Spravovat zdroje zpráv (RSS/Atom) →
          </a>
        </div>
        <div className="mb-6 rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
          <h2 className="text-base font-bold text-slate-900">Podsekce zpráv</h2>
          <p className="mt-1 text-sm text-slate-600">
            Vytvořte kategorie jako Ekonomika, Byznys apod. a přiřaďte je ke zprávám.
          </p>
          <div className="mt-3 flex flex-wrap gap-2">
            <input
              className="min-w-[12rem] rounded-lg border border-slate-300 px-3 py-2 text-sm"
              placeholder="Název nové podsekce"
              value={newCategoryName}
              onChange={(e) => setNewCategoryName(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter") addCategory();
              }}
            />
            <button
              type="button"
              disabled={categorySaving}
              className="rounded-lg bg-slate-900 px-4 py-2 text-sm font-semibold text-white hover:bg-slate-800 disabled:opacity-60"
              onClick={addCategory}
            >
              Přidat podsekci
            </button>
          </div>
          {categories.length > 0 ? (
            <ul className="mt-4 space-y-2">
              {categories.map((cat) => (
                <li
                  key={cat.id}
                  className="flex flex-wrap items-center gap-2 rounded-lg border border-slate-100 bg-slate-50/80 px-3 py-2 text-sm"
                >
                  {editingCategoryId === cat.id ? (
                    <>
                      <input
                        className="min-w-[10rem] rounded border border-slate-300 px-2 py-1"
                        value={editingCategoryName}
                        onChange={(e) => setEditingCategoryName(e.target.value)}
                      />
                      <button type="button" className="text-emerald-700 underline" onClick={saveCategory}>
                        Uložit
                      </button>
                      <button
                        type="button"
                        className="text-slate-600 underline"
                        onClick={() => {
                          setEditingCategoryId(null);
                          setEditingCategoryName("");
                        }}
                      >
                        Zrušit
                      </button>
                    </>
                  ) : (
                    <>
                      <span className="font-medium text-slate-900">{cat.name}</span>
                      <button type="button" className="text-slate-700 underline" onClick={() => startEditCategory(cat)}>
                        Upravit
                      </button>
                      <button
                        type="button"
                        className="text-red-700 underline"
                        onClick={() => deleteCategory(cat)}
                      >
                        Smazat
                      </button>
                    </>
                  )}
                </li>
              ))}
            </ul>
          ) : (
            <p className="mt-3 text-sm text-slate-500">Zatím žádné podsekce.</p>
          )}
        </div>

        <div className="mb-4 flex flex-wrap items-center gap-2">
          <button
            type="button"
            className="rounded-lg bg-slate-900 px-4 py-2 text-sm font-semibold text-white hover:bg-slate-800"
            onClick={openNew}
          >
            Nová zpráva
          </button>
          <button
            type="button"
            className="rounded-lg border border-slate-300 px-4 py-2 text-sm text-slate-700 hover:bg-slate-50"
            onClick={load}
          >
            Obnovit
          </button>
        </div>

        {editingId ? (
          <div className="mb-6 rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
            <h2 className="mb-3 text-lg font-bold text-slate-900">
              {editingId === "new" ? "Nová zpráva" : "Upravit zprávu"}
            </h2>
            <div className="grid gap-3">
              <label className="block text-sm">
                <span className="font-medium text-slate-700">Název</span>
                <input
                  className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2"
                  value={form.title}
                  onChange={(e) => setForm((f) => ({ ...f, title: e.target.value }))}
                />
              </label>
              <label className="block text-sm">
                <span className="font-medium text-slate-700">Podsekce (volitelně)</span>
                <select
                  className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 bg-white"
                  value={form.category_id}
                  onChange={(e) => setForm((f) => ({ ...f, category_id: e.target.value }))}
                >
                  <option value="">Bez podsekce</option>
                  {categories.map((cat) => (
                    <option key={cat.id} value={cat.id}>{cat.name}</option>
                  ))}
                </select>
              </label>
              <label className="block text-sm">
                <span className="font-medium text-slate-700">Krátký popis (volitelně)</span>
                <input
                  className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2"
                  value={form.summary}
                  onChange={(e) => setForm((f) => ({ ...f, summary: e.target.value }))}
                />
              </label>
              <ArticleCoverImageField
                value={form.cover_image_url}
                onChange={(cover_image_url) => setForm((f) => ({ ...f, cover_image_url }))}
              />
              <label className="block text-sm">
                <span className="font-medium text-slate-700">URL slug (volitelně)</span>
                <input
                  className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 font-mono text-sm"
                  placeholder="automaticky z názvu"
                  value={form.slug}
                  onChange={(e) => setForm((f) => ({ ...f, slug: e.target.value }))}
                />
              </label>
              <div className="block text-sm">
                <span className="font-medium text-slate-700">Text zprávy</span>
                <ArticleRichEditor
                  value={form.body}
                  onChange={(body) => setForm((f) => ({ ...f, body }))}
                  minHeight={240}
                />
              </div>
              {form.body.trim() ? (
                <div className="rounded-lg border border-slate-200 bg-slate-50/50 p-4">
                  <div className="mb-2 text-xs font-semibold uppercase tracking-wide text-slate-500">
                    Náhled
                  </div>
                  <ArticleBodyView body={form.body} />
                </div>
              ) : null}
              <label className="flex items-center gap-2 text-sm text-slate-700">
                <input
                  type="checkbox"
                  checked={form.published}
                  onChange={(e) => setForm((f) => ({ ...f, published: e.target.checked }))}
                />
                Publikováno (viditelné v mobilní aplikaci)
              </label>
            </div>
            <div className="mt-4 flex flex-wrap gap-2">
              <button
                type="button"
                disabled={saving}
                className="rounded-lg bg-emerald-700 px-4 py-2 text-sm font-semibold text-white hover:bg-emerald-600 disabled:opacity-60"
                onClick={save}
              >
                {saving ? "Ukládám…" : "Uložit"}
              </button>
              <button
                type="button"
                className="rounded-lg border border-slate-300 px-4 py-2 text-sm text-slate-700"
                onClick={closeForm}
              >
                Zrušit
              </button>
            </div>
          </div>
        ) : null}

        {err ? <p className="mb-4 text-sm text-red-600">{err}</p> : null}
        {loading ? (
          <p className="text-sm text-slate-600">Načítám…</p>
        ) : rows.length === 0 ? (
          <p className="text-sm text-slate-600">Zatím žádné zprávy.</p>
        ) : (
          <div className="overflow-x-auto rounded-xl border border-slate-200 bg-white">
            <table className="w-full min-w-[640px] text-left text-sm">
              <thead className="border-b border-slate-200 bg-slate-50 text-slate-600">
                <tr>
                  <th className="px-3 py-2 font-semibold w-16">Náhled</th>
                  <th className="px-3 py-2 font-semibold">Název</th>
                  <th className="px-3 py-2 font-semibold">Podsekce</th>
                  <th className="px-3 py-2 font-semibold">Stav</th>
                  <th className="px-3 py-2 font-semibold">Publikováno</th>
                  <th className="px-3 py-2 font-semibold">Akce</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => {
                  const coverUrl = getArticleCoverImageUrl(row);
                  return (
                  <tr key={row.id} className="border-b border-slate-100">
                    <td className="px-3 py-2">
                      {coverUrl ? (
                        <img
                          src={coverUrl}
                          alt=""
                          className="h-12 w-16 rounded border border-slate-200 object-cover bg-slate-100"
                          referrerPolicy="no-referrer"
                        />
                      ) : (
                        <span className="text-xs text-slate-400">—</span>
                      )}
                    </td>
                    <td className="px-3 py-2">
                      <div className="font-medium text-slate-900">{row.title}</div>
                      {row.summary ? (
                        <div className="text-slate-500">{row.summary}</div>
                      ) : null}
                    </td>
                    <td className="px-3 py-2 text-slate-600">
                      {row.category_name || "—"}
                    </td>
                    <td className="px-3 py-2">
                      {row.published ? (
                        <span className="rounded bg-emerald-100 px-2 py-0.5 text-emerald-800">Publikováno</span>
                      ) : (
                        <span className="rounded bg-slate-100 px-2 py-0.5 text-slate-600">Koncept</span>
                      )}
                    </td>
                    <td className="px-3 py-2 text-slate-600">{formatDate(row.published_at)}</td>
                    <td className="px-3 py-2">
                      <div className="flex flex-wrap gap-2">
                        <button
                          type="button"
                          className="text-slate-700 underline"
                          onClick={() => openEdit(row.id)}
                        >
                          Upravit
                        </button>
                        <button
                          type="button"
                          className="text-slate-700 underline"
                          onClick={() => togglePublished(row)}
                        >
                          {row.published ? "Skrýt" : "Publikovat"}
                        </button>
                        <button
                          type="button"
                          className="text-red-700 underline"
                          onClick={() => doDelete(row.id)}
                        >
                          Smazat
                        </button>
                      </div>
                    </td>
                  </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </AppShell>
  );
}

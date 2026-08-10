import React from "react";

/**
 * Zabrání celostránkové bílé obrazovce při neošetřené výjimce ve větvi Manager Exploreru
 * (/explore) — analogicky k CatalogSearchErrorBoundary pro /search/catalog.
 */
export default class ExploreErrorBoundary extends React.Component {
  constructor(props) {
    super(props);
    this.state = { error: null };
  }

  static getDerivedStateFromError(error) {
    return { error };
  }

  componentDidCatch(error) {
    // eslint-disable-next-line no-console
    console.error("Manager Explorer failed:", error);
  }

  handleRetry = () => {
    this.setState({ error: null });
    if (typeof this.props.onRetry === "function") {
      this.props.onRetry();
    } else {
      window.location.reload();
    }
  };

  render() {
    if (this.state.error) {
      const msg =
        this.state.error && typeof this.state.error.message === "string"
          ? this.state.error.message
          : String(this.state.error ?? "");
      return (
        <div
          className="rounded-2xl border border-rose-200 bg-rose-50/95 text-rose-950 p-8 text-center space-y-4 shadow-sm m-6"
          role="alert"
        >
          <p className="text-base font-semibold">Manager Explorer se nepodařilo zobrazit.</p>
          <p className="text-sm opacity-90">
            Výsledek analýzy zůstává uložený na serveru — zkuste to prosím znovu.
          </p>
          {msg ? <p className="text-sm opacity-95 break-words font-mono leading-relaxed">{msg}</p> : null}
          <button
            type="button"
            onClick={this.handleRetry}
            className="inline-flex items-center justify-center gap-2 px-4 py-2.5 rounded-xl border border-rose-300 bg-white hover:bg-rose-50 text-sm font-medium text-rose-950"
          >
            Zkusit znovu
          </button>
        </div>
      );
    }
    return this.props.children;
  }
}

package claudechat;

/** Where world data came from, which says how current it is. */
public enum Provenance {
	/** Read from the running game. */
	LIVE("live"),
	/** Read from the region files, which can be up to an autosave (5 minutes) behind. */
	SAVED("last save"),
	/** Worked out from the seed for land that was never generated. */
	PREDICTED("predicted from the seed (chunk not generated)");

	private final String label;

	Provenance(String label) {
		this.label = label;
	}

	/** How the tools show it to Claude. */
	public String label() {
		return label;
	}
}

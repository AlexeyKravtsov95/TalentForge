"""Route trusted workflow probe data through the guarded production CLI."""

from talent_data.ota.automation.delivery_routing import main

if __name__ == "__main__":
    raise SystemExit(main())

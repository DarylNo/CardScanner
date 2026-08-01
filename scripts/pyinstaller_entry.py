"""PyInstaller entry point — a plain script so --onefile has a real main."""
from mtg_card_scanner.launch import main

if __name__ == "__main__":
    main()

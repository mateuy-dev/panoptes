# Installs Panoptes for the current user. Run it from any project folder afterwards:
#   panoptes           terminal CLI (needs Java 21+ on PATH)
#   panoptes-desktop   desktop app (bundles its own Java runtime)
PREFIX ?= $(HOME)/.local
BIN_DIR := $(PREFIX)/bin
APP_DIR := $(PREFIX)/share/panoptes
JAR := core/build/libs/panoptes-1.0.0.jar
DESKTOP_APP := desktop/build/compose/binaries/main/app/Panoptes

.PHONY: build install uninstall

build:
	./gradlew -q :core:fatJar :desktop:createDistributable

install: build
	rm -rf "$(APP_DIR)"
	mkdir -p "$(APP_DIR)" "$(BIN_DIR)"
	cp $(JAR) "$(APP_DIR)/panoptes.jar"
	cp -r $(DESKTOP_APP) "$(APP_DIR)/desktop"
	printf '#!/bin/sh\nexec java -jar "%s/panoptes.jar" "$$@"\n' "$(APP_DIR)" > "$(BIN_DIR)/panoptes"
	printf '#!/bin/sh\nexec "%s/desktop/bin/Panoptes" "$$@"\n' "$(APP_DIR)" > "$(BIN_DIR)/panoptes-desktop"
	chmod +x "$(BIN_DIR)/panoptes" "$(BIN_DIR)/panoptes-desktop"
	@echo "Installed panoptes and panoptes-desktop in $(BIN_DIR)"

uninstall:
	rm -rf "$(APP_DIR)" "$(BIN_DIR)/panoptes" "$(BIN_DIR)/panoptes-desktop"

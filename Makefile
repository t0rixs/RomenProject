# RomenProject Makefile
#
# 使い方:
#   make help        # 一覧
#   make setup       # 初回セットアップ（npm install）
#   make dev         # PC側 (Vite + 受信サーバ) を起動
#   make reverse     # adb reverse を貼る
#   make install     # THINKLET にアプリをビルド & インストール
#   make start       # install + reverse + 端末でアプリ起動
#   make logs        # logcat を流し見る

# ---- 設定 ----
RECV_PORT         ?= 5174
VIEWER_DIR        := RomenViewer
LOGGER_DIR        := RomenLogger
LOGGER_PACKAGE    := com.example.romenlogger
LOGGER_ACTIVITY   := $(LOGGER_PACKAGE)/.MainActivity

.PHONY: help setup dev viewer server install reverse start launch ready logs devices clean-data uninstall doctor kill-ports

help:
	@awk 'BEGIN{FS=":.*##"; print "Targets:"} /^[a-zA-Z_-]+:.*##/{printf "  \033[36m%-14s\033[0m %s\n",$$1,$$2}' $(MAKEFILE_LIST)

# --- 初回セットアップ ---
setup: ## npm install (Viewer 側)
	cd $(VIEWER_DIR) && npm install

# --- 開発実行 ---
dev: kill-ports ## Vite + 受信サーバを起動 (5173 / 5174)
	cd $(VIEWER_DIR) && npm run dev

viewer: ## Vite だけ起動
	cd $(VIEWER_DIR) && npm run vite

server: kill-ports ## 受信サーバだけ起動
	cd $(VIEWER_DIR) && npm run server

kill-ports: ## 5173 / $(RECV_PORT) を使っているプロセスを終了
	@for p in 5173 $(RECV_PORT); do \
		pids=$$(lsof -ti tcp:$$p 2>/dev/null); \
		if [ -n "$$pids" ]; then \
			echo "killing pid(s) on port $$p: $$pids"; \
			kill $$pids 2>/dev/null || true; \
			sleep 1; \
			pids2=$$(lsof -ti tcp:$$p 2>/dev/null); \
			if [ -n "$$pids2" ]; then \
				echo "  still alive, sending SIGKILL: $$pids2"; \
				kill -9 $$pids2 2>/dev/null || true; \
			fi; \
		else \
			echo "port $$p is free"; \
		fi; \
	done

# --- THINKLET 接続 ---
devices: ## adb devices 表示
	adb devices

reverse: ## THINKLET → PC のポート転送 (tcp:$(RECV_PORT))
	adb reverse tcp:$(RECV_PORT) tcp:$(RECV_PORT)
	@echo "OK: adb reverse tcp:$(RECV_PORT) tcp:$(RECV_PORT)"

# --- Android アプリ ---
install: ## RomenLogger をビルドして実機にインストール
	cd $(LOGGER_DIR) && ./gradlew installDebug

uninstall: ## RomenLogger をアンインストール
	adb uninstall $(LOGGER_PACKAGE) || true

launch: ## RomenLogger を実機で起動 (要 install 済)
	adb shell am start -n $(LOGGER_ACTIVITY)

start: install reverse launch ## install + reverse + launch

ready: install reverse ## install + reverse → 続いて手動で `make dev`
	@echo "==== READY ===="
	@echo "次は別ターミナルで: make dev"
	@echo "ブラウザ: http://localhost:5173"

# --- デバッグ ---
logs: ## logcat を RomenLogger タグだけ表示
	adb logcat -s RomenLogger:I AndroidRuntime:E

clean-data: ## 受信した記録データをすべて削除（PC側のみ）
	rm -rf $(VIEWER_DIR)/data/recordings
	@echo "removed: $(VIEWER_DIR)/data/recordings"

doctor: ## 環境チェック（adb / node / java / device）
	@echo "--- node ---"; node --version || echo "Node が見つかりません"
	@echo "--- npm ---"; npm --version || echo "npm が見つかりません"
	@echo "--- adb ---"; adb --version | head -1 || echo "adb が見つかりません"
	@echo "--- java (システム) ---"; java -version 2>&1 | head -1 || echo "java が見つかりません"
	@echo "--- Gradle が使う JDK (gradle.properties) ---"; \
		grep -E "^org\.gradle\.java\.home" $(LOGGER_DIR)/gradle.properties || echo "(未設定)"
	@echo "--- 接続中の端末 ---"; adb devices

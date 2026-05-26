# RomenProject

THINKLET（ウェアラブル端末）で記録した **GPS + 加速度（振動）** を、有線USB経由で PC に送信し、ブラウザ上の地図とグラフで可視化するシステムです。

## プロジェクトの目的

THINKLET を装着した状態で歩行・移動しながら **振動と GPS を同時記録** し、**路面状態を把握する**ためのデバイス／ソフトウェアです。

具体的には、振動の強さ・パターンから次のような路面状態を**推認**することを狙います。

- 路面が舗装されているか／未舗装か
- ひび割れ・段差・マンホール段差などの局所的な凹凸
- 石・異物が転がっているなどの異常
- 区間ごとの「走りにくさ／歩きにくさ」傾向

GPS と紐づくため、**地図上のどの区間でどの程度の振動が発生したか**を可視化でき、点検・調査・路面評価の補助ツールとして利用できます。

```
┌────────────────────┐    USB (adb reverse)     ┌───────────────────────────┐
│  THINKLET          │ ───────────────────────▶ │  PC                        │
│  RomenLogger       │   POST /api/upload       │   server.mjs  :5174        │
│  (Android Kotlin)  │                          │     └─ ./data/recordings/  │
│                    │                          │   Vite        :5173        │
│  - GPS 1Hz         │                          │     └─ RomenViewer (React) │
│  - Accel 50Hz      │                          │        Leaflet + Recharts  │
└────────────────────┘                          └───────────────────────────┘
```

## ディレクトリ

| パス | 役割 |
|---|---|
| `RomenLogger/` | THINKLET 用 Android アプリ（Kotlin / Jetpack Compose） |
| `RomenViewer/` | ブラウザ用ビューア（React + Vite）と受信サーバ（Node） |
| `Makefile`     | 開発作業のショートカット |

## 必要なもの

- **macOS / Linux**（Windows は WSL を想定）
- **Node.js** 18+
- **JDK 17**（Temurin 推奨。`RomenLogger/gradle.properties` で参照しているパスを必要に応じて編集）
- **Android Platform Tools**（`adb` が PATH に通っていること）
- **THINKLET LC01** などの Android 端末

## クイックスタート

```bash
# 1) 初回のみ
make setup

# 2) 環境チェック
make doctor

# 3) THINKLET を USB で接続して
make start         # ビルド → 端末にインストール → adb reverse → 端末でアプリ起動

# 4) 別ターミナルで PC 側サーバ起動
make dev           # http://localhost:5173 が開けば OK
```

## 使い方

1. THINKLET の RomenLogger でセッションを録る（START → STOP）
2. 履歴一覧で **「📤 PCへ送信」** をタップ
3. PC 側ブラウザのヘッダに **「受信中…」→「受信完了」** が表示される
4. 一覧が自動更新され、新しいセッションをクリックすると地図 + 振動グラフが見られる

## Make ターゲット

| コマンド | 内容 |
|---|---|
| `make help`        | 一覧 |
| `make setup`       | `npm install`（Viewer） |
| `make dev`         | Vite + 受信サーバ起動（5173 / 5174） |
| `make viewer`      | Vite だけ起動 |
| `make server`      | 受信サーバだけ起動 |
| `make install`     | THINKLET にアプリをビルド & インストール |
| `make uninstall`   | THINKLET からアプリ削除 |
| `make launch`      | 端末上でアプリを起動 |
| `make reverse`     | `adb reverse tcp:5174 tcp:5174` |
| `make start`       | install + reverse + launch |
| `make ready`       | install + reverse |
| `make logs`        | logcat（RomenLogger タグ） |
| `make devices`     | `adb devices` |
| `make clean-data`  | 受信済データを全削除 |
| `make doctor`      | adb / node / java / 端末接続を確認 |

## ポート

| ポート | 用途 |
|---|---|
| **5173** | Vite dev（ブラウザはここを開く）|
| **5174** | 受信サーバ（THINKLET → PC へのアップロード先）|

`adb reverse tcp:5174 tcp:5174` により、THINKLET から `http://localhost:5174` が PC の 5174 に転送されます。USB が外れると切れるので再接続時は `make reverse` を再実行してください。

## トラブルシュート

- **「サーバ未接続」表示** → `make dev` を起動しているか確認
- **THINKLET から送信失敗** → `make reverse` を再実行（USB 抜き差し後は必須）
- **Gradle が JDK で落ちる** → `RomenLogger/gradle.properties` の `org.gradle.java.home` を自分の JDK 17 のパスに合わせる
- **ログを見る** → `make logs`

## データ形式

PC 側 `RomenViewer/data/recordings/{id}/` 以下に保存:

| ファイル | 内容 |
|---|---|
| `meta.json`   | セッションのメタ情報 |
| `gps.csv`     | 生 GPS（time, lat, lon, ...） |
| `accel.csv`   | 生加速度（50Hz） |
| `merged.json` | 100ms バケット集約 + 最近傍 GPS 結合（ブラウザ表示用） |

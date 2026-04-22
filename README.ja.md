# ROI Explorer for Fiji

ROI Explorer は、ROI ファイルを tree/table 形式で閲覧・編集・計測・整理するための Fiji/ImageJ プラグインです。

ROI フォルダと ROI ZIP を 1 つの TreeTable で扱えます。画像に bind して overlay 表示したり、ROI の edit / split / measure を ROI Manager より扱いやすい形で行うことを目的にしています。

English README: [README.md](./README.md)

Fiji メニュー:

- `Plugins > ROI Explorer`

## 主な機能

- ROI フォルダ、ネストしたフォルダ、ROI ZIP を 1 つの TreeTable で閲覧
- ROI ファイルやフォルダの drag & drop による整理
- 開いている画像への bind と overlay 表示
- 単一 ROI の edit
- `Knife` / `Seed Split` による ROI の分割
- `Keep Largest Part`, `Remove Small Islands`, `Fill Holes`, `Expand`, `Shrink` などの cleanup
- ROI Manager との import / export
- folder と ZIP の相互変換
- ImageJ 標準設定に従う `Measure ROI`
- 永続設定を使う `Group Measure`
- メイン操作の undo / redo
- edit 中 selection 変更の undo / redo

## Release から導入

1. GitHub Releases から `ROI_Explorer_Fiji.jar` をダウンロード
2. Fiji の `plugins` ディレクトリに配置
3. Fiji を再起動

release に置くのは ROI Explorer の plugin jar のみです。Fiji/ImageJ 本体やその他依存物は、それぞれのライセンスに従います。

## ソースからビルド

必要なもの:

- Java 8
- Maven
- Fiji/ImageJ 1.54p 相当の環境

ビルド:

```bash
mvn -q package
```

生成物:

```text
target/ROI_Explorer_Fiji.jar
```

## 基本的な使い方

### 起動

`Plugins > ROI Explorer` から起動します。

起動時に Fiji でアクティブな画像があれば、自動でその画像に bind します。

### 画像への bind

まだ bind されていない場合は、Fiji で画像を開いてから ROI Explorer の `Bind` を使います。

bind 後は、現在 view にある visible ROI が画像上に overlay 表示されます。`Z`, `C`, `T` の projection 設定も反映されます。

### ROI の閲覧と整理

- ROI をダブルクリックすると `Edit`
- folder / ZIP をダブルクリックすると展開 / 折りたたみ
- ROI ファイルやフォルダを drag & drop で移動 / コピー
- ROI は ZIP に drop 可能
- folder / ZIP / 混合選択は ZIP に drop 不可
- `More` から duplicate, ZIP/unZIP, ROI Manager 連携, group measurements などにアクセス

### 単一 ROI の edit

ROI を 1 つ選んで `Edit` を押します。

edit mode では画像上の active selection を使います。

- `Save`: 編集結果を元 ROI に保存
- `Cancel`: 編集開始前の ROI に戻す
- `Undo` / `Redo`: edit session 中の selection 変更を追跡
- `Cleanup...`: 現在 selection に対して cleanup tool を適用

### 単一 ROI の split

ROI を 1 つ選んで `Split Tools` を開きます。

split mode は edit mode と独立しています。対象は selection ではなく、選択した ROI ファイルそのものです。

現在の split tool:

- `Knife`
- `Seed Split`

結果確認後:

- `Save Split Results`: 分割結果を元 ROI と同じ folder または同じ ROI ZIP に保存
- 既定では元 ROI は残る
- `Replace original` を有効にすると元 ROI を置き換える
- `Cancel`: 元 ROI は変更しない

### Measure ROI

`Measure ROI` は ImageJ 標準の計測フローに合わせています。

- ImageJ 標準の `Set Measurements...` で項目を設定
- ROI Explorer の `Measure ROI` で選択 ROI を計測

### Group Measure

`Group Measure` は ROI Explorer 独自の永続設定を使います。

- `Set Group Measurements...` で一度設定
- 以後は `Group Measure` を押すだけで同じ設定で計測

主な group metrics:

- ROI 数、面積集計
- 3D volume / surface area
- sphericity
- intensity 集計
- centroid 座標
- 最遠 2 点距離と端点座標
- 2D の long axis / short axis

長さ系の列名は固定 `um` ではなく、画像 metadata の calibration unit を使います。

## Undo / Redo

undo / redo には 2 つのスコープがあります。

- メイン session history
  - add, delete, rename, move, duplicate, ZIP/unZIP, save, split-save など
- edit session history
  - edit mode 中の selection 変更だけ

メイン history は session-local で、ROI Explorer を閉じると消えます。

edit / split の追跡も session-local です。tree 全体に恒久 ID を付けるのではなく、編集中の ROI だけを session 単位で追跡します。

## 注意

- Visibility は session state のみです。`Hide`, `Show`, `Toggle Visibility` は ROI file に独自 metadata を書きません
- Group Measure の結果は bind 画像の calibration と intensity に依存します
- 一部のショートカットは、画像 window がアクティブなときに ImageJ 既存ショートカットと重なる場合があります

## ライセンス

このプロジェクト本体は MIT License で配布しています。詳細は [LICENSE](./LICENSE) を参照してください。

依存ライブラリはそれぞれ元のライセンスに従います。

## 状態

まだ開発中ですが、現在の実装でも ROI の閲覧、編集、分割、計測はかなり使える段階です。今後も UI や細部挙動は調整される可能性があります。

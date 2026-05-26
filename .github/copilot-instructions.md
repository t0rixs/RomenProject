## Interaction Rule
回答の末尾では、必ず `vscode_askQuestions` ツールを使って次のアクション選択UIを表示すること。

質問の構成：
- header: `next_action`
- question: `次のステップを選んでください。`
- options: 現在の文脈に沿った具体的な提案を3つ程度（各 `label` に行動内容を記載）
- 最後のオプションは必ず `label: "その他（下に自由入力）"` とすること
- `multiSelect: false`
- `allowFreeformInput: true`（ユーザが自由にテキストを追記できるようにする）

これにより、ユーザが選択肢を選ぶか自由入力するかで会話を途切れなく継続できる。

※回答の本編（コードや解説）のスタイルについては制限しませんが、この末尾の構成だけは常に維持してください。
# 自包含DeepSeek文本审查

通过WSL原生dsh headless调用，复用同一脚本处理后续候选，不再复制日期目录中的review.mjs。输入提示应完整包含代码、实测和限制，明确禁止工具、读文件、执行和联网。

```powershell
wsl.exe --exec /usr/bin/node /mnt/d/Workspace/AcceleratedNavigation-forge-1.20.1/tools/review/deepseek-review.mjs /mnt/d/path/packet.txt /mnt/d/path/new-review-directory
node --test tools/review/deepseek-review.test.mjs
```

输出目录的父目录必须存在，输出目录必须尚不存在。单包最多8500 UTF-8字节，900秒超时，超时终止进程组。脚本核对当前配置为deepseek/deepseek-v4.1-flash，不修改配置；保存原始prompt、实际transmitted、answer、process及exit和SHA256。

退出0表示调用正常结束、有可识别结论且settings哈希不变，包含REQUEST_CHANGES；不等于审查接受。应读取answer及mustFixesClear判断实际阻断。文本审查不替代本地实测和身份核验。

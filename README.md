# 坐标变换注册站 (Coordinate Transform Registry)

为测绘团队提供坐标参考系（CRS）登记、带有效范围与精度的变换边管理、自动链路选择，
并保存每次作业的原始输入、所选链与结果。纯 Java 17 + JDK 内置 HTTP 服务器，
无任何在线地图或远程坐标服务依赖。

## 构建与演示

```bash
./gradlew classes                 # 构建检查
./gradlew test                    # 全部测试（28 个）
./gradlew run --args='--host 127.0.0.1 --port 5220'
# 浏览器打开 http://127.0.0.1:5220 ，页面标题为“坐标变换注册站”
```

可选参数：`--data <path>` 指定持久化文件（默认 `data/registry-store.json`）。

## 核心规则

- **链路选择**：先按几何区域覆盖过滤（边界命中视为覆盖），再取累计误差最小，
  最后以路径签名（边 id + 正/反向）字典序稳定打破平局；结果可重复。
- **反经线**：区域以 `minLon > maxLon` 表示跨越 ±180°；几何外包盒在经度跨度 >180°
  时自动按反经线表达，不会退化成普通 min/max 全球盒。
- **计算前校验**：纬度越界、经度越界、非有限数字（如 `1e999`→∞）、不闭合多边形
  一律拒绝；批量作业逐项独立成功/失败，失败项保留诊断信息。
- **逆变换**：边必须显式声明 `invertible: true` 且仿射矩阵行列式非零才可反向使用。
- **回路一致性**：新边在共享有效区域的 3×3 抽样点上与既有同端点路径比较，
  偏差超过容差 `1e-6` 时进入 `PENDING`，不参与默认路径；确认必须填写理由，
  确认后产生新版本并记录理由。
- **历史绑定**：每个作业保存 `registryVersion`、完整路径、误差、结果与
  SHA-256 指纹；注册表后续变更不会改写历史作业。
- **可移植导出**：`GET /api/export` 输出完整版本化存储文档；导入后路径选择、
  误差序列化（`Double.toString` 规范）与作业指纹完全一致。

## JSON API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET  | `/api/state` | 当前版本、CRS、边、作业 id |
| POST | `/api/crs` | 登记参考系 `{id,name,description}` |
| POST | `/api/edges` | 登记变换边（不一致自动 PENDING） |
| POST | `/api/edges/{id}/confirm` | 确认待处理边，body 需含 `reason` |
| POST | `/api/transform` | 单几何变换，返回全部候选路径及接受/排除原因 |
| POST | `/api/jobs` | 批量作业，逐项成功或失败 |
| GET  | `/api/jobs/{id}` | 取历史作业（不可变快照） |
| GET  | `/api/export` | 完整可移植存储文档 |
| POST | `/api/import` | 导入存储文档替换当前状态 |

边格式示例：

```json
{
  "id": "e1", "src": "WGS84", "dst": "GCJ02",
  "region": {"minLon": 70, "minLat": 0, "maxLon": 140, "maxLat": 60},
  "accuracy": 0.5,
  "affine": [1, 0, 0.006, 0, 1, -0.001],
  "invertible": true
}
```

`affine` 为六参数二维仿射变换 `x'=a*x+b*y+tx; y'=c*x+d*y+ty`。
跨反经线区域示例：`{"minLon": 170, "minLat": 0, "maxLon": -170, "maxLat": 60}`。

## 网页功能

- SVG 绘制参考系节点与变换图（实线 ACTIVE，虚线 PENDING，可逆边标注 ⇄）。
- 粘贴 GeoJSON（Point / LineString / Polygon）执行单对象变换。
- 候选路径表格列出累计误差、是否覆盖、接受/排除原因。
- Canvas 叠加显示输入（蓝）与输出（红）几何。
- 批量作业提交、版本日志、JSON 导出/导入。

## 测试覆盖

反经线外包盒与相交、边界恰好命中、不可逆边、稳定平局、回路不一致与确认、
批量部分失败、历史结果绑定、导出/导入可复现性、非有限输入、不闭合多边形、
以及 HTTP API 端到端流程（含持久化文件）。

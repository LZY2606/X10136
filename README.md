# 坐标变换注册站

一个零外部服务依赖的 Java 坐标变换登记与批处理服务。系统用有向图管理 CRS 与仿射变换边，按“有效范围 → 累计误差上界 → 稳定字典序”选择路径，并保存输入、选中的链、输出与当时的注册表快照。

## 构建与演示

```bash
./gradlew classes
./gradlew test
./gradlew run --args='--host 127.0.0.1 --port 5220'
```

打开 <http://127.0.0.1:5220>，页面标题为“坐标变换注册站”。项目不调用在线地图或远程坐标服务；测试运行器在仓库源码内，不依赖远程测试库。

## 核心规则

- 每条边声明源/目标 CRS、二维仿射变换、精度、正向范围；只有显式 `inverseSafe=true` 时才提供逆变换，并必须提供逆变换精度和范围。
- 路径只使用 ACTIVE 边；PENDING 边和不可逆方向会出现在候选解释中，但不参与默认路径。
- 范围先逐点检查，再比较累计误差；误差相同时按跳数和 `edgeId|direction` 字典序稳定打破平局。
- 误差沿路径传播：`E_path = e_new + ||J_new|| * E_previous`，其中 `J` 是仿射线性部分的算子范数（最大奇异增益）。
- 反经线包围盒使用 `crossesAntimeridian=true` 且 `minX > maxX` 表示，例如 `170` 到 `-170`；GeoJSON 中相邻经度跳变超过 180 度也会被识别为反经线线段。
- 计算前拒绝非有限 JSON 数字、地理坐标越界和未精确闭合的 Polygon。
- 批量作业逐项独立返回 SUCCESS/FAILED；失败项保留错误码、诊断和路径解释。
- 新边若与已有同方向路径在抽样点上的差异超过 `3 * (已有路径累计误差 + 新边精度)`，进入 PENDING；确认必须填写理由并产生新版本。
- 作业内嵌注册表版本、指纹和快照；后续注册更新不会改变历史作业。导出再导入保留版本、指纹和规范化 JSON 序列化。

## JSON API

- `GET /api/registry`：当前 CRS、ACTIVE 边、PENDING 边、版本和指纹。
- `POST /api/crs`：登记 CRS。
- `POST /api/edges`：登记边并执行回路/平行路径抽样一致性检查。
- `POST /api/edges/confirm`：`{ "edgeId": "...", "reason": "..." }` 确认待确认边。
- `POST /api/plan`：只计算候选路径、排除原因和误差，不保存作业。
- `POST /api/jobs`：执行批处理并持久化。
- `GET /api/jobs` / `GET /api/jobs/{id}`：历史作业列表和详情。
- `GET /api/export` / `POST /api/import`：可移植 JSON 导出/导入。

### 作业示例

```json
{
  "sourceCrs": "WGS84_GEO",
  "targetCrs": "LOCAL_GRID",
  "items": [
    {
      "id": "point-1",
      "geometry": { "type": "Point", "coordinates": [139.7, 35.65] }
    }
  ]
}
```

单个条目也可以覆盖 `sourceCrs` 和 `targetCrs`。支持 Point、LineString、Polygon、Feature 和 FeatureCollection 粘贴。

## 持久化

默认数据目录是 `./data`，可用 JVM 系统属性更换：

```bash
./gradlew run --args='--host 127.0.0.1 --port 5220' -Dctr.data=/tmp/ctr-data
```

- `data/registry.json`：当前注册表和待确认边，采用临时文件加原子替换。
- `data/registry-audit.jsonl`：版本变化和确认理由。
- `data/jobs/{fingerprint16}.json`：作业请求、结果、路径解释、错误上界和注册表快照。

指纹只由规范化数据内容、版本和注册表指纹决定，不含墙钟时间，因此相同输入在导入相同数据后得到相同路径、误差序列化和作业指纹。

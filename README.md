# 坐标变换注册站 (Coordinate Transform Registry Station)

一个**离线、零外部依赖**的坐标参考系 / 变换登记服务：登记坐标参考系（CRS）与
带**有效范围**和**精度说明**的变换边，为一批几何对象选择可用变换链，输出变换
结果、**累计误差上界**和完整的路径解释；原始坐标、所选链、结果与当时注册表版本
一起持久化，注册表后续更新**不会回改历史作业**。

不调用任何在线地图或远程坐标服务（纯 JDK 内置 HTTP 服务器 + 手写 JSON）。

## 构建与运行

```bash
./gradlew classes                                   # 构建检查
./gradlew test                                      # 内置测试套件（86 个断言）
./gradlew run --args='--host 127.0.0.1 --port 5220' # 启动网页/API
```

打开 http://127.0.0.1:5220 ，页面标题为 **坐标变换注册站**。首次启动会写入
确定性演示数据（可用网页“重置为演示数据”或 `POST /api/reseed` 重建）。
运行状态默认存于 `./data/station-store.json`，可用 `--data DIR` 修改。

## 选择规则

对每个几何的**每个顶点**，按以下顺序选择链路，整条链必须覆盖全部顶点：

1. **覆盖范围**：每一步的有效域必须包含当前点（边界点算在内；地理 CRS 的
   bbox 允许 `minLon > maxLon` 或越界经度表示跨越反经线，按 360° 周期判定，
   不用朴素 min/max 包围盒；多边形域在解包经度空间内做点在环内判断）。
2. **累计误差上界**：边精度按“目标 CRS 单位的绝对误差界”声明；前序误差经
   后续边线性部分传播后再累加，取各顶点最坏值。
3. **稳定平局**：误差相同 → 步数更少 → 边 id 序列字典序。

边可声明逆变换；只有矩阵安全可逆（行列式非零且条件数代理 ≥ `1e-12`）或
显式 `invertible:true` 时才生成反向弧；显式 `invertible:false` 永远不可逆向。
`pending`（待确认）/`disabled` 边不参与默认路径。

## 计算前拒绝

- 非有限数字（`NaN`/`Infinity`，JSON 解析层直接拒绝）
- 地理 CRS 纬度超出 `[-90, 90]`（±90 恰好合法）
- 多边形环点数不足、首尾不闭合（`ring-not-closed`）
- 非 2D 坐标、未知 CRS、无覆盖链（每个对象独立失败并保留诊断）

## 回路一致性与版本

登记新边时，会在其抽样点上与已有图中能够闭合的链比较；最大偏差超过
`loopTolerance` 时边进入 `pending`，保存差异证据且**不参与默认路径**。
确认必须填写理由，可确认启用或停用，并写入版本日志（`registryVersion`
单调递增）。

作业指纹（SHA-256，canonical JSON）绑定**请求输入 + 注册表图内容哈希**，
不依赖单调版本号，因此导出后导入到新环境重跑，路径选择、误差序列化与指纹
完全一致。

## HTTP API

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/state` | CRS、边（含状态/精度/有效域）、版本日志 |
| POST | `/api/crs` | 登记 CRS `{id,name,kind,units}` |
| POST | `/api/edges` | 登记变换边（回路不一致自动 pending） |
| POST | `/api/edges/{id}/confirm` | `{reason,state:active|disabled}`，形成新版本 |
| POST | `/api/jobs` | 批量作业，对象可独立成功/失败 |
| GET | `/api/jobs` / `/api/jobs/{id}` | 历史作业列表 / 详情（绑定当时版本） |
| GET | `/api/export` | 可移植导出（即持久化文件本体） |
| POST | `/api/import` | 整体导入导出文档 |
| POST | `/api/reseed` | 恢复内置演示数据 |

作业请求示例：

```json
{
  "sourceCrs": "WGS84",
  "targetCrs": "GRID-B",
  "objects": [
    {"id":"p1","geometry":{"type":"Point","coordinates":[139.7671,35.6812]}},
    {"geometry":{"type":"LineString","coordinates":[[139.7,35.65],[139.75,35.7]]}}
  ]
}
```

也接受直接粘贴 GeoJSON `FeatureCollection` / `Feature` / `GeometryCollection`。
每个返回项含 `status`、选中链 `selectedPath` / `selectedEdgeIds`、逐步解释
`steps`、所有候选链的接受/排除原因 `candidates`、`cumulativeError`，失败项含
`error.code` 与 `error.message`。

## 网页

- CRS 与变换图（正向实线箭头、可逆反向细箭头、pending 紫色虚线）
- 粘贴 GeoJSON → 批量作业
- 候选路径为何接受 / 排除（点不包含的边、有效域、逐步累计误差、平局结果）
- 输入（蓝）/输出（橙）叠加画布
- 待确认边确认表单（理由必填）、历史作业查看、JSON 导入/导出

## 目录

```
src/main/java/ctstation   核心模型/路径选择/持久化/HTTP 服务（零第三方依赖）
src/main/resources/web    单页前端（原生 JS + canvas，无 CDN）
src/test/java/ctstation   零依赖断言框架与测试套件
```

测试覆盖：反经线 bbox/多边形与批处理、边界恰好命中、不可逆边、稳定平局、
回路不一致自动待确认、批量部分失败、历史结果绑定、非有限输入（JSON 与
API 两层）、导出/导入指纹与误差序列化一致性、纬度/闭合环校验。

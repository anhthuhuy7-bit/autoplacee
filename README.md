# Schem Builder (Fabric 1.21.11, client)
Đặt file `.litematic` / `.schem` vào `.minecraft/schematics/`.

Lệnh:
- `/sbuild list`            liệt kê file
- `/sbuild load <file>`     nạp schematic
- `/sbuild origin`          lấy vị trí đứng hiện tại làm gốc (góc dưới-min của schematic)
- `/sbuild start` / `stop`  bắt đầu / dừng
- `/sbuild speed <1-10>`    số block mỗi tick
- `/sbuild reach <r>`       tầm đặt (mặc định 4.5)
- `/sbuild status`

Build: `gradle build` -> `build/libs/schembuilder-1.0.0.jar` (cần Java 21 + mạng để tải Loom/Minecraft).

## Hướng & trạng thái block
Engine mô phỏng `Block#getPlacementState` ở client với nhiều tổ hợp (block đỡ, mặt click, độ cao click, yaw, pitch),
chọn tổ hợp cho ra đúng state rồi gửi gói xoay + click tương ứng.
Hỗ trợ: stairs (facing/half), slab (bottom/top/double), log/pillar (axis), piston/observer/dispenser/hopper (facing),
nút/cần gạt/grindstone (face), đèn/biển/banner (rotation 16 hướng), wall torch/sign...
Tự bỏ qua: nửa trên của cửa/hoa cao, đầu giường, chất lỏng, block không có item.
Sneak tự động khi click vào block có GUI (rương, cửa, nút, bàn chế tạo...).
Không so khớp: powered, lit, open, waterlogged, hinge cửa, kết nối hàng rào/kính (game tự tính).

## Scaffold (tự đặt block tựa rồi dọn)
Khi một block không có chỗ tựa, mod tìm đường (tối đa 6 ô) từ block đó tới chỗ có block rắn, đặt các block scaffold
nối đường đó, đặt block đích, rồi tự đập scaffold khi không còn block nào cần nó. Hết build sẽ dọn nốt.
- `/sbuild scaffold on|off`   bật/tắt (mặc định bật)
- `/sbuild scaffold item`     dùng block đang cầm làm scaffold (mặc định: cobblestone, dirt, netherrack...)
- `/sbuild clean`             dọn mọi scaffold đã đặt (cần đứng trong tầm với)
Lưu ý: block gắn vào scaffold (đuốc, biển...) sẽ rơi khi scaffold bị dọn; mod ưu tiên block thật trước, scaffold sau.

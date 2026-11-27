# SenceLab

Ứng dụng Android quản lý map cờ và Mod skin cho Liên Quân Mobile. Phiên bản hiện tại: **2.3.2**.

## Tải ứng dụng

- [Tải SenceLab 2.3.2](https://github.com/hack10293u-blip/SenceCoLqm/releases/tag/2.3.2)
- Gói ứng dụng: `com.tmod.kgvn`
- Android 8.0 trở lên. APK debug để thử nghiệm.

## Tính năng

- **Kho**: danh mục Map cờ online, ảnh xem trước và Mod skin SkinChess.
- **Lịch Sử**: mở lại nội dung đã dùng để áp dụng; map cờ đã dùng áp dụng lại không nhập pass; Mod skin không hỏi pass và không tính lượt tải.
- **Nhập**: Map cờ nhận `.assetbundle`; Map 5v5 và Skin nhận `.zip`. Danh sách hiển thị MB, có Áp dụng và xác nhận Xóa.
- **Fix Resources**: làm mới tài nguyên khi đã kết nối nhưng chưa ghi được dữ liệu, giữ bản sao lưu theo số thứ tự.
- **Gỡ mod**: xóa tệp map cờ trong Resources. SkinChess có nút Xóa riêng để khôi phục tệp gốc đã lưu trước khi áp dụng.
- **Trang chủ**: trạng thái map và trạng thái Skin và nút khôi phục skin gốc hiển thị riêng; Fix ngân hàng chỉ hiện với Shizuku/ADB WiFi đang kết nối.
- **Cập nhật**: kiểm tra và thông báo bản mới từ GitHub Releases.

Nút **Chọn Z** trong tab Nhập hiện chưa gắn chức năng. Nút chọn ZArchiver của SAF V2 vẫn giữ cách hoạt động từ 2.0.8.

## Cấp quyền

Vào **Quyền → Cấp quyền bộ nhớ**, chọn Shizuku, ADB WiFi, SAF hoặc ghi trực tiếp trên Android 9/LDPlayer. SAF V1 dùng quyền thư mục; SAF V2 chọn một tệp có sẵn, không đủ để giải nén ZIP hoặc ghi tệp skin khác.

Ứng dụng kiểm tra và xác minh dữ liệu sau khi ghi. Đóng trò chơi trước khi áp dụng hoặc làm mới tài nguyên. Khả năng truy cập Android/data phụ thuộc thiết bị và quyền thực tế; chưa kiểm chứng bản này trên Android 16/HyperOS 3.

## Thông tin mã nguồn

Mã Android của phiên bản hiện tại nằm trong `android-source`. Tag `2.3.2` lưu đúng mã phát hành; tag `2.0.8` được giữ làm nền. Những bản 2.1.x đã ngừng phát hành.

Đường dẫn map cờ:

`/storage/emulated/0/Android/data/com.garena.game.kgvn/files/Resources/1.64.1/assetbundle/modules/autochess/scene_artist_autochess_01.assetbundle`

Đường dẫn SkinChess:

`/storage/emulated/0/Android/data/com.garena.game.kgvn/files/Resources/1.64.1/Databin/Client/AutoChess/ResACChessInfoCfg.bytes`

## Liên hệ

- [Telegram](https://t.me/ttt_cth)
- [TikTok](https://tiktok.com/@autochess.lqm)
- [YouTube](https://www.youtube.com/@battuthanhthan)

ADB WiFi ghép đôi qua thông báo hoặc nhập mã thủ công, không quay hay đọc màn hình. Tab Nhập thử ghi trực tiếp vào Resources của Garena/Memecheat và xác minh sau ghi; quyền thực tế vẫn do Android quản lý.

Từ 2.3.2, LDPlayer trực tiếp dùng gói com.garena.game.kgvn64x. Các quyền khác và tab Nhập giữ nguyên đường dẫn trước đó.

Từ 2.3.2, riêng map của LDPlayer trực tiếp dùng com.garena.game.kgvn64x/files/Extra/2022.V3/assetbundle/modules/autochess/scene_artist_autochess_01.assetbundle. Các quyền khác, Skin và Fix Resources giữ nguyên.

Bản 2.3.2 khóa màn hình dọc. Khi gỡ map qua LDPlayer trực tiếp, app khôi phục bundle gốc thay vì xóa file. Các quyền khác giữ cách gỡ trước đó.

Bản 2.3.2 giúp Fix Resources qua ADB WiFi truyền một ZIP và giải nén trực tiếp trên thiết bị; Shizuku làm mới dịch vụ sau nâng cấp để ổn định thao tác skin.

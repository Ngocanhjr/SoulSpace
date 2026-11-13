1. Kiểm tra PostgreSQL và môi trường
SELECT
    version(),
    current_database(),
    current_user,
    current_schema();

2/ Kiểm tra pgvector có khả dụng không
SELECT
    name,
    default_version,
    installed_version
FROM pg_available_extensions
WHERE name = 'vector';

3/ Bật pgvector (bật extension)
CREATE EXTENSION IF NOT EXISTS vector;

kiểm tra:
SELECT
    extversion AS installed_version,
    '[1,0,0]'::vector <=> '[0,1,0]'::vector AS cosine_distance
FROM pg_extension
WHERE extname = 'vector';

4/ chuẩn bị chạy flyway để tạo blogs
Tải postgresql 17.2 
Mở terminal: 
Set-Location D:\3-SoulSpace
psql --version
java -version

5/ hướng dẫn connect psql vào neon
5.1/ Vào Neon → Connect. Chọn branch production, database neondb và role chủ database. Tắt Connection pooling để lấy direct host. Host là phần sau dấu @ và trước /neondb trong chuỗi kết nối. Hướng dẫn kết nối Neon

5.2/ Trong cửa sổ PowerShell đang mở, chạy lệnh dưới đây. Thay <direct-host> và <owner-role> bằng thông tin từ Neon.
Set-Location D:\3-SoulSpace

$neonCa = Join-Path $env:TEMP 'neon-ca-bundle.pem'
Invoke-WebRequest -UseBasicParsing -Uri 'https://curl.se/ca/cacert.pem' -OutFile $neonCa

$neonConn = $neonConn -replace 'sslrootcert=system', "sslrootcert='$($neonCa.Replace('\', '/'))'"

& $psqlExe $neonConn -W -c 'SELECT current_database(), current_user;'
Nhập password: npg_ZmN6RTHVBY2b (là mật khẩu trên neon trước dấu @)

6/ Bước tiếp theo là tạo hai role cho SoulSpace. Trong cùng cửa sổ PowerShell, nhập hai mật khẩu mới và khác nhau. Lưu lại hai mật khẩu để điền vào .env.neon ở bước sau.
$env:DB_PASSWORD = [pscredential]::new('runtime', (Read-Host 'Mat khau moi cho soulspace_runtime' -AsSecureString)).GetNetworkCredential().Password
neoN@#403005
$env:FLYWAY_PASSWORD = [pscredential]::new('migrator', (Read-Host 'Mat khau moi cho soulspace_migrator' -AsSecureString)).GetNetworkCredential().Password
flyWay@#054030

kiểm tra so 2 cái mật khẩu
(-not [string]::IsNullOrWhiteSpace($env:DB_PASSWORD)) -and
(-not [string]::IsNullOrWhiteSpace($env:FLYWAY_PASSWORD)) -and
($env:DB_PASSWORD -cne $env:FLYWAY_PASSWORD)

& $psqlExe $neonConn -W -f .\infra\postgres\provision.sql
-> nhập password neon


-- Seed data (subset of the classic Pet Store catalog), en_US locale.
-- Mirrors the shape of the legacy Populate-UTF8.xml.
-- Uses H2 MERGE (upsert) so re-running is idempotent — safe across app restarts
-- and multiple test application contexts sharing the in-memory DB.

-- Categories
MERGE INTO category (catid) KEY(catid) VALUES ('FISH'), ('DOGS'), ('CATS'), ('BIRDS');
MERGE INTO category_details (catid, locale, name, image, descn) KEY(catid, locale) VALUES
 ('FISH', 'en_US', 'Fish', '/images/fish_icon.gif', 'Aquatic creatures'),
 ('DOGS', 'en_US', 'Dogs', '/images/dogs_icon.gif', 'Loyal companions'),
 ('CATS', 'en_US', 'Cats', '/images/cats_icon.gif', 'Independent felines'),
 ('BIRDS', 'en_US', 'Birds', '/images/birds_icon.gif', 'Feathered friends');

-- Products
MERGE INTO product (productid, catid) KEY(productid) VALUES
 ('FI-SW-01', 'FISH'), ('FI-FW-01', 'FISH'),
 ('K9-BD-01', 'DOGS'), ('K9-PO-02', 'DOGS'),
 ('FL-DSH-01', 'CATS'), ('AV-CB-01', 'BIRDS');
MERGE INTO product_details (productid, locale, name, image, descn) KEY(productid, locale) VALUES
 ('FI-SW-01', 'en_US', 'Angelfish', '/images/fish1.gif', 'Saltwater fish from Australia'),
 ('FI-FW-01', 'en_US', 'Koi', '/images/fish2.gif', 'Freshwater fish from Japan'),
 ('K9-BD-01', 'en_US', 'Bulldog', '/images/dog1.gif', 'Friendly dog from England'),
 ('K9-PO-02', 'en_US', 'Poodle', '/images/dog2.gif', 'Cuddly poodle'),
 ('FL-DSH-01', 'en_US', 'Manx', '/images/cat1.gif', 'Great for a apartment'),
 ('AV-CB-01', 'en_US', 'Amazon Parrot', '/images/bird1.gif', 'Great companion for years');

-- Items
MERGE INTO item (itemid, productid) KEY(itemid) VALUES
 ('EST-1', 'FI-SW-01'), ('EST-2', 'FI-SW-01'),
 ('EST-5', 'K9-BD-01'), ('EST-10', 'FL-DSH-01'), ('EST-18', 'AV-CB-01');
MERGE INTO item_details (itemid, locale, listprice, unitcost, image, descn, attr1) KEY(itemid, locale) VALUES
 ('EST-1', 'en_US', 16.50, 10.00, '/images/fish1.gif', 'Large Angelfish', 'Large'),
 ('EST-2', 'en_US', 16.50, 10.00, '/images/fish1.gif', 'Small Angelfish', 'Small'),
 ('EST-5', 'en_US', 18.50, 12.00, '/images/dog1.gif', 'Female Puppy Bulldog', 'Female Puppy'),
 ('EST-10', 'en_US', 58.50, 12.00, '/images/cat1.gif', 'Tailless Manx', 'Tailless'),
 ('EST-18', 'en_US', 193.50, 92.00, '/images/bird1.gif', 'Adult Male Amazon Parrot', 'Adult Male');

-- ============================ ja_JP (Japanese) ============================
MERGE INTO category_details (catid, locale, name, image, descn) KEY(catid, locale) VALUES
 ('FISH', 'ja_JP', '魚', '/images/fish_icon.gif', '水生生物'),
 ('DOGS', 'ja_JP', '犬', '/images/dogs_icon.gif', '忠実な仲間'),
 ('CATS', 'ja_JP', '猫', '/images/cats_icon.gif', '自立した猫'),
 ('BIRDS', 'ja_JP', '鳥', '/images/birds_icon.gif', '羽のある友達');
MERGE INTO product_details (productid, locale, name, image, descn) KEY(productid, locale) VALUES
 ('FI-SW-01', 'ja_JP', 'エンゼルフィッシュ', '/images/fish1.gif', 'オーストラリア産の海水魚'),
 ('FI-FW-01', 'ja_JP', '鯉', '/images/fish2.gif', '日本産の淡水魚'),
 ('K9-BD-01', 'ja_JP', 'ブルドッグ', '/images/dog1.gif', 'イングランド産の親しみやすい犬'),
 ('K9-PO-02', 'ja_JP', 'プードル', '/images/dog2.gif', '愛らしいプードル'),
 ('FL-DSH-01', 'ja_JP', 'マンクス', '/images/cat1.gif', 'アパートに最適'),
 ('AV-CB-01', 'ja_JP', 'アマゾンオウム', '/images/bird1.gif', '長年の良き伴侶');
MERGE INTO item_details (itemid, locale, listprice, unitcost, image, descn, attr1) KEY(itemid, locale) VALUES
 ('EST-1', 'ja_JP', 16.50, 10.00, '/images/fish1.gif', '大きいエンゼルフィッシュ', '大'),
 ('EST-2', 'ja_JP', 16.50, 10.00, '/images/fish1.gif', '小さいエンゼルフィッシュ', '小'),
 ('EST-5', 'ja_JP', 18.50, 12.00, '/images/dog1.gif', 'メスの子犬ブルドッグ', 'メスの子犬'),
 ('EST-10', 'ja_JP', 58.50, 12.00, '/images/cat1.gif', '尾のないマンクス', '尾なし'),
 ('EST-18', 'ja_JP', 193.50, 92.00, '/images/bird1.gif', '成体オスのアマゾンオウム', '成体オス');

-- ============================ zh_CN (Chinese) ============================
MERGE INTO category_details (catid, locale, name, image, descn) KEY(catid, locale) VALUES
 ('FISH', 'zh_CN', '鱼', '/images/fish_icon.gif', '水生动物'),
 ('DOGS', 'zh_CN', '狗', '/images/dogs_icon.gif', '忠诚的伙伴'),
 ('CATS', 'zh_CN', '猫', '/images/cats_icon.gif', '独立的猫'),
 ('BIRDS', 'zh_CN', '鸟', '/images/birds_icon.gif', '有羽毛的朋友');
MERGE INTO product_details (productid, locale, name, image, descn) KEY(productid, locale) VALUES
 ('FI-SW-01', 'zh_CN', '神仙鱼', '/images/fish1.gif', '来自澳大利亚的海水鱼'),
 ('FI-FW-01', 'zh_CN', '锦鲤', '/images/fish2.gif', '来自日本的淡水鱼'),
 ('K9-BD-01', 'zh_CN', '斗牛犬', '/images/dog1.gif', '来自英格兰的友好犬'),
 ('K9-PO-02', 'zh_CN', '贵宾犬', '/images/dog2.gif', '可爱的贵宾犬'),
 ('FL-DSH-01', 'zh_CN', '曼岛猫', '/images/cat1.gif', '非常适合公寓'),
 ('AV-CB-01', 'zh_CN', '亚马逊鹦鹉', '/images/bird1.gif', '多年的好伴侣');
MERGE INTO item_details (itemid, locale, listprice, unitcost, image, descn, attr1) KEY(itemid, locale) VALUES
 ('EST-1', 'zh_CN', 16.50, 10.00, '/images/fish1.gif', '大神仙鱼', '大'),
 ('EST-2', 'zh_CN', 16.50, 10.00, '/images/fish1.gif', '小神仙鱼', '小'),
 ('EST-5', 'zh_CN', 18.50, 12.00, '/images/dog1.gif', '雌性幼犬斗牛犬', '雌性幼犬'),
 ('EST-10', 'zh_CN', 58.50, 12.00, '/images/cat1.gif', '无尾曼岛猫', '无尾'),
 ('EST-18', 'zh_CN', 193.50, 92.00, '/images/bird1.gif', '成年雄性亚马逊鹦鹉', '成年雄性');

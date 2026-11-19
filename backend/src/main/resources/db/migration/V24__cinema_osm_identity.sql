-- V24: give a cinema an identity, and let an address not exist.
--
-- Both changes have one cause. V18's 130 rows came from Nominatim, which is a
-- geocoder: it ranks its answers, caps them, and returns a formatted address
-- for everything. So a hand-checked 130 happened to have unique names within
-- each city and an address on every row. Neither is true of what OpenStreetMap
-- actually holds, and the next batch comes from Overpass, which is a bulk
-- extract and hands back exactly what mappers typed.
--
-- Measured against the real data for the same 40 cities: 991 named cinemas,
-- 52 colliding city and name pairs covering 141 rows, and 62% with no address
-- tags of any kind. India is 23% addressed against the UK's 83%, so dropping
-- the unaddressed ones would leave the smaller market better covered than the
-- bigger one.


-- ---------------------------------------------------- identity, not the name

-- The OSM node or way id, which V18 already put in a comment on all 130 rows.
-- A comment is not reachable by a query, so re-running the ingestion had no
-- way to tell a cinema it already had from a new one and would have had to
-- match on names. These two columns are the whole reason the ingestion becomes
-- repeatable rather than a one-off.
--
-- Two columns rather than one, because an OSM id is only unique within its
-- type: node 123 and way 123 are different objects in different tables.
--
-- A lookup table for the three types would match what V17 did for currency and
-- V22 for country, and is not worth it here. That set is fixed by OSM's data
-- model rather than by anything this project decides, and it will not gain a
-- fourth member. A CHECK says the same thing without a join.
ALTER TABLE cinema ADD COLUMN osm_type text;
ALTER TABLE cinema ADD COLUMN osm_id   bigint;

-- Matched on name and city, which is safe precisely because the constraint
-- this migration is about to drop guarantees that pair is unique for the rows
-- that exist now. Doing the backfill before the drop is not an accident.
--
-- If any row fails to match, its osm_type stays null, the SET NOT NULL below
-- fails, and Flyway rolls the whole file back. The migration checks its own
-- backfill, which is better than a count I would have to remember to run.
UPDATE cinema c
   SET osm_type = v.osm_type,
       osm_id   = v.osm_id
  FROM (VALUES
    ('Cinema Theatre', 'Vijayawada', 'node', 11665450938),
    ('PVR Cinema', 'Vijayawada', 'node', 11618920788),
    ('Gold Digital Cinema', 'Guwahati', 'node', 6884796785),
    ('Rupashree Cinema', 'Guwahati', 'node', 11934635857),
    ('Alpana Cinema Hall', 'Patna', 'node', 8121345398),
    ('Cinema Center', 'Patna', 'way', 333587642),
    ('Mona Cinema', 'Patna', 'node', 1356116434),
    ('Kiran Cinema', 'Chandigarh', 'way', 111984812),
    ('DT Cinema', 'Delhi', 'node', 4236862896),
    ('INOX Nehru Place Cinema', 'Delhi', 'node', 7040536185),
    ('PVR DFL Promenade Cinema', 'Delhi', 'node', 7040547485),
    ('PVR ECX Chanakyapuri Cinema', 'Delhi', 'node', 7040474785),
    ('PVR Sangam Cinema', 'Delhi', 'node', 1116435329),
    ('Virat Cinema', 'Delhi', 'way', 353176442),
    ('Everyman Cinema', 'Birmingham', 'node', 4683735545),
    ('Mockingbird Cinema & Kitchen', 'Birmingham', 'node', 3407012343),
    ('Omniplex Cinema', 'Birmingham', 'way', 127892324),
    ('The Electric Cinema', 'Birmingham', 'way', 103195744),
    ('Cottage Road Cinema', 'Leeds', 'way', 201424001),
    ('Everyman Cinema', 'Leeds', 'way', 1511133724),
    ('Odeon', 'Leeds', 'node', 5998749306),
    ('Cineworld', 'Liverpool', 'node', 7876395843),
    ('Everyman', 'Liverpool', 'node', 9910021784),
    ('Odeon', 'Liverpool', 'way', 1102667865),
    ('Picturehouse At FACT', 'Liverpool', 'node', 2482985464),
    ('Vue', 'Liverpool', 'way', 235492560),
    ('Woolton Cinema', 'Liverpool', 'way', 844287445),
    ('Barnet Everyman Cinema', 'London', 'way', 71380122),
    ('David Lean Cinema', 'London', 'node', 299124495),
    ('Electric Cinema', 'London', 'way', 357556094),
    ('Phoenix Cinema', 'London', 'node', 299028906),
    ('Prince Charles Cinema', 'London', 'way', 180594287),
    ('Rio Cinema', 'London', 'way', 224192326),
    ('Cineworld', 'Manchester', 'way', 237785643),
    ('HOME Cinema', 'Manchester', 'node', 3693252208),
    ('Northern Light Cinema Stretford', 'Manchester', 'node', 13978794338),
    ('ODEON Manchester Great Northern', 'Manchester', 'node', 307874659),
    ('Odeon Manchester Trafford Centre', 'Manchester', 'node', 2441956006),
    ('Vue', 'Manchester', 'node', 1510001469),
    ('Adlabs BIG Cinema', 'Ahmedabad', 'node', 440320409),
    ('Anupam Cinema', 'Ahmedabad', 'way', 318515857),
    ('Mango Plus Cinema', 'Ahmedabad', 'node', 14331539862),
    ('Rupalee Cinema', 'Ahmedabad', 'way', 327564303),
    ('Galaxy Cinema', 'Rajkot', 'way', 801000791),
    ('Inox Cinema', 'Surat', 'node', 2632158479),
    ('PVR Cinema', 'Surat', 'node', 2632158921),
    ('Rajhans Cinema', 'Surat', 'node', 2630742319),
    ('Minakshi Cinema', 'Ranchi', 'way', 287217261),
    ('PJP Cinema', 'Ranchi', 'node', 13172620878),
    ('Popkorn Cinema', 'Ranchi', 'node', 13172620879),
    ('SRS Cinema', 'Ranchi', 'way', 902966145),
    ('Elgin Cinema', 'Bengaluru', 'node', 1469265737),
    ('Kalpana Cinema', 'Bengaluru', 'way', 405141210),
    ('Mohan Cinema', 'Bengaluru', 'node', 1794163355),
    ('Sri Vinayaka Cinema', 'Bengaluru', 'way', 1274603989),
    ('Swagath Murali Cinema', 'Bengaluru', 'way', 346459984),
    ('Sathyam Cinema', 'Mysuru', 'node', 1574263692),
    ('Sagarika Cinema', 'Kochi', 'way', 624637344),
    ('EMAX Cinema', 'Kozhikode', 'node', 9790941302),
    ('Gtracks Cinema', 'Thiruvananthapuram', 'node', 7517789782),
    ('SP Cinema Peyad', 'Thiruvananthapuram', 'way', 930523295),
    ('Star Cinema', 'Thiruvananthapuram', 'way', 797655284),
    ('Vimala Theatre', 'Thiruvananthapuram', 'node', 1237550819),
    ('DDX Cinema', 'Bhopal', 'node', 4781136427),
    ('Jyoti Cinema', 'Bhopal', 'node', 4782970915),
    ('Sangam Cinema', 'Bhopal', 'node', 5049920324),
    ('Chandan Cinema', 'Mumbai', 'way', 203143523),
    ('Kalpana Cinema Hall', 'Mumbai', 'way', 353181441),
    ('Mehul Cinema', 'Mumbai', 'way', 632245751),
    ('Metro INOX Cinema', 'Mumbai', 'way', 46717574),
    ('Movietime Cinema', 'Mumbai', 'way', 135137729),
    ('Strand Cinema', 'Mumbai', 'way', 353407534),
    ('AM Cinema', 'Nagpur', 'node', 6926124653),
    ('Smruti Cinema', 'Nagpur', 'way', 444727842),
    ('Alankar Cinema', 'Pune', 'way', 107173244),
    ('Liberty Cinema', 'Pune', 'way', 52782995),
    ('Victory Cinema', 'Pune', 'node', 671453565),
    ('Ashok Cinema', 'Thane', 'way', 681516924),
    ('Malhar Cinema', 'Thane', 'node', 4663156696),
    ('Aarti Cinema', 'Ludhiana', 'way', 209205407),
    ('Manju Cinema', 'Ludhiana', 'node', 458763223),
    ('Raikhy Cinema', 'Ludhiana', 'way', 209212359),
    ('Sangeet Cinema', 'Ludhiana', 'node', 1791841244),
    ('Shingar Cinema', 'Ludhiana', 'node', 2639783065),
    ('Suriya Cinema', 'Ludhiana', 'node', 1686787875),
    ('Cinepolis', 'Jaipur', 'node', 7049512594),
    ('Galaxy Cinema', 'Jaipur', 'node', 3078037353),
    ('Inox Movies Cinema', 'Jaipur', 'node', 6938645049),
    ('Raj Mandir Cinemas', 'Jaipur', 'node', 10904794879),
    ('Kohinoor Cinema', 'Jodhpur', 'node', 3956812972),
    ('Cineworld', 'Glasgow', 'node', 3668838706),
    ('The Grosvenor Cinema', 'Glasgow', 'node', 437381250),
    ('Cosmos Cinema 4K', 'Coimbatore', 'way', 1248293136),
    ('Gokulam Cinema', 'Coimbatore', 'way', 497332987),
    ('KG Big Cinema', 'Coimbatore', 'way', 191231187),
    ('Krithika Cinema Hall', 'Coimbatore', 'way', 461696407),
    ('Lakshmi Cinema Hall', 'Coimbatore', 'node', 1470928245),
    ('Bhelec Cinema', 'Tiruchirappalli', 'way', 1535747126),
    ('LA Cinema', 'Tiruchirappalli', 'node', 12287751625),
    ('Flanker Cinema', 'Hyderabad', 'node', 3390852922),
    ('INOX Cinema', 'Hyderabad', 'node', 890312487),
    ('Manju Cinema', 'Hyderabad', 'node', 582824598),
    ('Shanti Cinema Theatre', 'Hyderabad', 'way', 142300260),
    ('Tarakarama Cinema', 'Hyderabad', 'way', 357130502),
    ('Panna Palace Cinema', 'Agra', 'node', 12647728169),
    ('Rajeev Cinema', 'Agra', 'way', 303616715),
    ('Sheila Cinema Hall', 'Agra', 'node', 3102600281),
    ('Tulsi Cinema', 'Agra', 'node', 3102616857),
    ('Galaxie Cinema', 'Ghaziabad', 'node', 2069359469),
    ('New Vasanth Cinema', 'Kanpur', 'node', 1291412679),
    ('Carnival Cinema', 'Lucknow', 'node', 5372681324),
    ('Novelty Cinema Aliganj', 'Lucknow', 'node', 4186033390),
    ('Shubham Cinema', 'Lucknow', 'way', 1461484957),
    ('Sudharshan Cinema', 'Lucknow', 'node', 14202561801),
    ('Anurag Cinema', 'Meerut', 'way', 1302811937),
    ('BLW Cinema Hall', 'Varanasi', 'node', 292730020),
    ('Carnival Cinema PDR', 'Varanasi', 'node', 8848598037),
    ('IP Vijaya Mall Cinema Hall', 'Varanasi', 'node', 11697410090),
    ('Sajjan Cinema', 'Varanasi', 'node', 8844949735),
    ('Natraj Cinema', 'Dehradun', 'node', 13247612788),
    ('Orient Cinema', 'Dehradun', 'node', 1705583059),
    ('Payal Cinema', 'Dehradun', 'node', 1710188691),
    ('Ritz Cinema', 'Dehradun', 'node', 5001731238),
    ('Bijoli Cinema Hall', 'Kolkata', 'way', 239402258),
    ('Kalika Cinema', 'Kolkata', 'way', 175776604),
    ('Moonlight Cinema', 'Kolkata', 'way', 525517318),
    ('Paradise Cinema', 'Kolkata', 'node', 4368104697),
    ('Priya Cinema', 'Kolkata', 'way', 187711647),
    ('Ujjala Cinema', 'Kolkata', 'way', 175777378),
    ('Vishal Cinema', 'Siliguri', 'node', 14067300691)
  ) AS v(name, city, osm_type, osm_id)
 WHERE c.name = v.name
   AND c.city = v.city;

ALTER TABLE cinema ALTER COLUMN osm_type SET NOT NULL;
ALTER TABLE cinema ALTER COLUMN osm_id   SET NOT NULL;

ALTER TABLE cinema
    ADD CONSTRAINT cinema_osm_type_known CHECK (osm_type IN ('node', 'way', 'relation'));

-- The real identity of a cinema row, and the conflict target every future
-- generated migration will name.
ALTER TABLE cinema
    ADD CONSTRAINT cinema_osm_identity UNIQUE (osm_type, osm_id);


-- -------------------------------------------- the name constraint comes off

-- V16 said two cinemas in one city cannot share a name, and the comment above
-- it said that across cities they can because every chain has an INOX in more
-- than one place. That is the argument against itself one city down: London
-- has ten cinemas called Everyman and Bengaluru has six called PVR Cinemas.
-- The constraint was never protecting anything, it just happened to hold over
-- 130 curated rows, and identity now lives on the OSM id where it belongs.
ALTER TABLE cinema DROP CONSTRAINT cinema_unique_in_city;


-- ------------------------------------------------ an address may not exist

-- Two thirds of OSM's cinemas carry no addr:* tags at all. The three ways to
-- keep this NOT NULL were all worse than dropping it: bulk reverse geocoding
-- through Nominatim is prohibited by their usage policy, doing it through a
-- commercial geocoder makes a vendor's guess from coordinates look like
-- something a mapper wrote, and composing "Jaipur, Rajasthan" is inventing
-- data, which is the one thing V18's header promises the cinema rows do not
-- contain.
--
-- So the column tells the truth: there is a street line where somebody mapped
-- one, and nothing where nobody did. The map already answers where a building
-- is, exactly, and the API already sends the distance to it.
ALTER TABLE cinema ALTER COLUMN address DROP NOT NULL;
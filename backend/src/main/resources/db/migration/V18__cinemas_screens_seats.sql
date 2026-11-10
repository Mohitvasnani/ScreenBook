-- Real cinemas, and the rooms inside them.
--
-- The cinema rows are OpenStreetMap data, retrieved through Nominatim rather
-- than Overpass, which refuses this network outright. Names, coordinates and
-- addresses are real; the OSM node or way id is in a comment on every row so
-- any of them can be checked. Licence is ODbL, so "© OpenStreetMap
-- contributors" has to appear wherever this is displayed.
--
-- Everything else here is generated. The screenings, the seats and the
-- prices are invented, and the README says so. No cinema chain publishes
-- seat maps or showtimes in a form anyone can use, and pretending otherwise
-- would be the only dishonest thing in this project.
--
-- The coverage is uneven: six cinemas in Kolkata, one in Chandigarh, and
-- nothing at all in Chennai or Indore, which obviously have cinemas and have
-- simply not been tagged amenity=cinema. That gap is real open data rather
-- than a bug, and filling it with invented rows would hide the only
-- interesting thing about using a real source.
--
-- Names are title cased on the way in, because OSM has "raj mandir cinemas"
-- and "NATRAJ CINEMA" and nobody normalises user contributions. Closed
-- venues, a drive-in with no seat map to model, and entries Nominatim filed
-- 60km outside the city they were asked for are all dropped.

-- ----------------------------------------------------------- the cinemas

-- Currency and zone are derived from the state rather than listed per row,
-- because they are a fact about the country and repeating them 130 times is
-- 130 chances to type one wrong.
--
-- ST_SetSRID before the cast is not optional. ST_MakePoint returns a
-- geometry with SRID 0, and casting that to geography either errors or
-- silently assumes 4326 depending on the PostGIS version. Saying it out loud
-- costs nothing and removes the version dependency.
INSERT INTO cinema (name, city, state, address, currency, timezone, location)
SELECT v.name, v.city, v.state, v.address,
       CASE WHEN v.state IN ('England', 'Scotland') THEN 'GBP' ELSE 'INR' END,
       CASE WHEN v.state IN ('England', 'Scotland')
            THEN 'Europe/London' ELSE 'Asia/Kolkata' END,
       ST_SetSRID(ST_MakePoint(v.lon, v.lat), 4326)::geography
FROM (VALUES
  ('Cinema Theatre', 'Vijayawada', 'Andhra Pradesh', 'Gopal Reddy Road, Krishna Lanka, 520001', 80.627216, 16.509516),  -- n11665450938
  ('PVR Cinema', 'Vijayawada', 'Andhra Pradesh', 'MG Road, Patamata Lanka, 520001', 80.643563, 16.501091),  -- n11618920788
  ('Gold Digital Cinema', 'Guwahati', 'Assam', 'K.C. SEN ROAD PALTAN BAZAR THANA ROAD, Paltan Bazaar, 781001', 91.750597, 26.181041),  -- n6884796785
  ('Rupashree Cinema', 'Guwahati', 'Assam', 'Assam Trunk Road, Pan Bazar, 781001', 91.742522, 26.178276),  -- n11934635857
  ('Alpana Cinema Hall', 'Patna', 'Bihar', 'Ashok Raj Path, Digha, 800018', 85.088926, 25.647134),  -- n8121345398
  ('Cinema Center', 'Patna', 'Bihar', 'Station Road, 803210', 85.138673, 25.604373),  -- w333587642
  ('Mona Cinema', 'Patna', 'Bihar', 'East Gandhi Maidan Marg, Rajendra Nagar, 800001', 85.148686, 25.61802),  -- n1356116434
  ('Kiran Cinema', 'Chandigarh', 'Chandigarh', 'Jan Marg, Sector 22, 160023', 76.771288, 30.733603),  -- w111984812
  ('DT Cinema', 'Delhi', 'Delhi', 'Alaknanda, 110048', 77.240061, 28.541408),  -- n4236862896
  ('INOX Nehru Place Cinema', 'Delhi', 'Delhi', 'Nehru Place Road, Nehru Place, 110019', 77.251256, 28.549897),  -- n7040536185
  ('PVR DFL Promenade Cinema', 'Delhi', 'Delhi', 'Mall Back Entry Road, Vasant Kunj, 110067', 77.155731, 28.54279),  -- n7040547485
  ('PVR ECX Chanakyapuri Cinema', 'Delhi', 'Delhi', 'Tikendrajit Marg, Chanakyapuri, 110023', 77.191055, 28.584378),  -- n7040474785
  ('PVR Sangam Cinema', 'Delhi', 'Delhi', 'Major Somnath Marg, Ramakrishna Puram, 110022', 77.173364, 28.572677),  -- n1116435329
  ('Virat Cinema', 'Delhi', 'Delhi', 'Raja Ram Marg, Dakshinpuri, 110062', 77.237089, 28.523219),  -- w353176442
  ('Everyman Cinema', 'Birmingham', 'England', 'Commercial Street, Chinese Quarter, B1 1RF', -1.905646, 52.475549),  -- n4683735545
  ('Mockingbird Cinema & Kitchen', 'Birmingham', 'England', 'Gibb Street, Digbeth, B9 4AA', -1.884179, 52.475415),  -- n3407012343
  ('Omniplex Cinema', 'Birmingham', 'England', 'Parklands, Longbridge, B45 9JL', -2.008256, 52.398832),  -- w127892324
  ('The Electric Cinema', 'Birmingham', 'England', 'Station Street, Ladywood, B5 4DY', -1.898718, 52.476575),  -- w103195744
  ('Cottage Road Cinema', 'Leeds', 'England', 'Cottage Road, Headingley, LS6 4DD', -1.581748, 53.82701),  -- w201424001
  ('Everyman Cinema', 'Leeds', 'England', 'Albion Street, Lovell Park, LS1 5AY', -1.544002, 53.796794),  -- w1511133724
  ('Odeon', 'Leeds', 'England', 'Thorpe Park View, Manston, LS15 8GH', -1.425754, 53.800259),  -- n5998749306
  ('Cineworld', 'Liverpool', 'England', 'Speke Road, Garston, L24 8QB', -2.880538, 53.349303),  -- n7876395843
  ('Everyman', 'Liverpool', 'England', 'Whitechapel, Cavern Quarter, L1 6DA', -2.98644, 53.407184),  -- n9910021784
  ('Odeon', 'Liverpool', 'England', 'Paradise Street, Vauxhall, L1 8JF', -2.986714, 53.404529),  -- w1102667865
  ('Picturehouse At FACT', 'Liverpool', 'England', 'Wood Street, Vauxhall, L1 4DQ', -2.977651, 53.402236),  -- n2482985464
  ('Vue', 'Liverpool', 'England', 'Europa Boulevard, Seacombe, CH41 4PE', -3.023484, 53.392992),  -- w235492560
  ('Woolton Cinema', 'Liverpool', 'England', 'Mason Street, Woolton, L25 5JH', -2.867719, 53.375578),  -- w844287445
  ('Barnet Everyman Cinema', 'London', 'England', 'Great North Road, High Barnet, EN5 1AB', -0.187874, 51.646365),  -- w71380122
  ('David Lean Cinema', 'London', 'England', 'Katharine Street, Broad Green, CR9 1ET', -0.099012, 51.372132),  -- n299124495
  ('Electric Cinema', 'London', 'England', 'Portobello Road, Notting Hill, W11 2ED', -0.205065, 51.515524),  -- w357556094
  ('Phoenix Cinema', 'London', 'England', 'Fairlawn Avenue, Hampstead Garden Suburb, N2 9PS', -0.163709, 51.588508),  -- n299028906
  ('Prince Charles Cinema', 'London', 'England', 'Leicester Place, Covent Garden, WC2H 7BY', -0.130214, 51.511491),  -- w180594287
  ('Rio Cinema', 'London', 'England', 'Kingsland High Street, Dalston, E8 2PB', -0.07558, 51.54962),  -- w224192326
  ('Cineworld', 'Manchester', 'England', 'Kingsway, West Didsbury, M20 5PG', -2.218815, 53.409673),  -- w237785643
  ('HOME Cinema', 'Manchester', 'England', 'Jack Rosenthal Street, City Centre, M15 4RA', -2.246241, 53.473683),  -- n3693252208
  ('Northern Light Cinema Stretford', 'Manchester', 'England', 'Pinnington Lane, Gorse Hill, M32 9AH', -2.309487, 53.445711),  -- n13978794338
  ('ODEON Manchester Great Northern', 'Manchester', 'England', 'Deansgate, City Centre, M3 4EN', -2.249664, 53.476409),  -- n307874659
  ('Odeon Manchester Trafford Centre', 'Manchester', 'England', 'Barton Dock Road, Trafford Park, M17 8DF', -2.348004, 53.465935),  -- n2441956006
  ('Vue', 'Manchester', 'England', 'Withy Grove, City Centre, M4 2BS', -2.240369, 53.485263),  -- n1510001469
  ('Adlabs BIG Cinema', 'Ahmedabad', 'Gujarat', 'Drive-in Road, Bodakdev, 380054', 72.531028, 23.046577),  -- n440320409
  ('Anupam Cinema', 'Ahmedabad', 'Gujarat', 'Shri Khandubhai Desai Marg, Rajpur Gomtipur, 380023', 72.613243, 23.006629),  -- w318515857
  ('Mango Plus Cinema', 'Ahmedabad', 'Gujarat', 'New india colony, Nava Naroda, 380049', 72.677394, 23.063639),  -- n14331539862
  ('Rupalee Cinema', 'Ahmedabad', 'Gujarat', 'Rustom Cama Marg, Khadia, 380001', 72.579883, 23.02783),  -- w327564303
  ('Galaxy Cinema', 'Rajkot', 'Gujarat', 'Race Course Ring Road, 360001', 70.792098, 22.299366),  -- w801000791
  ('Inox Cinema', 'Surat', 'Gujarat', 'Dumas Road, 394518', 72.757276, 21.145559),  -- n2632158479
  ('PVR Cinema', 'Surat', 'Gujarat', 'Dumas Road, 394518', 72.766771, 21.155055),  -- n2632158921
  ('Rajhans Cinema', 'Surat', 'Gujarat', 'Dumas Road, 394518', 72.774286, 21.160726),  -- n2630742319
  ('Minakshi Cinema', 'Ranchi', 'Jharkhand', 'pahari mandir road, Mahuatoli, 834005', 85.311687, 23.378942),  -- w287217261
  ('PJP Cinema', 'Ranchi', 'Jharkhand', 'Ratu Road, Mahuatoli, 834008', 85.314713, 23.378404),  -- n13172620878
  ('Popkorn Cinema', 'Ranchi', 'Jharkhand', 'Ratu Road, Nawatoli, 834005', 85.300086, 23.381104),  -- n13172620879
  ('SRS Cinema', 'Ranchi', 'Jharkhand', 'Purluia Road, Konka, 834001', 85.338998, 23.368284),  -- w902966145
  ('Elgin Cinema', 'Bengaluru', 'Karnataka', 'Shivaji Road, Vasanth Nagar, 560051', 77.601634, 12.98516),  -- n1469265737
  ('Kalpana Cinema', 'Bengaluru', 'Karnataka', 'Kempegowda Road, Gandhinagar, 560053', 77.57721, 12.974471),  -- w405141210
  ('Mohan Cinema', 'Bengaluru', 'Karnataka', 'Hegganahalli Main Road, Sunkadakatte, 560091', 77.503672, 12.998649),  -- n1794163355
  ('Sri Vinayaka Cinema', 'Bengaluru', 'Karnataka', 'Varthur - Sarjapur Road, Gunjur, 560087', 77.744532, 12.935811),  -- w1274603989
  ('Swagath Murali Cinema', 'Bengaluru', 'Karnataka', '19th Cross Road, J.P PARK, 560054', 77.552487, 13.038445),  -- w346459984
  ('Sathyam Cinema', 'Mysuru', 'Karnataka', 'Manasara Road, Ittigegudu, 570001', 76.664312, 12.29762),  -- n1574263692
  ('Sagarika Cinema', 'Kochi', 'Kerala', 'Old NH 47, 682004', 76.282456, 9.94402),  -- w624637344
  ('EMAX Cinema', 'Kozhikode', 'Kerala', 'Mavoor-Medical College Road, 673008', 75.844213, 11.270074),  -- n9790941302
  ('Gtracks Cinema', 'Thiruvananthapuram', 'Kerala', 'Kadinamkulam Road, Kadinamkulam, 695303', 76.819528, 8.604585),  -- n7517789782
  ('SP Cinema Peyad', 'Thiruvananthapuram', 'Kerala', 'Peyad - Puliyarakonam Road, Peyad, 695573', 77.019438, 8.513812),  -- w930523295
  ('Star Cinema', 'Thiruvananthapuram', 'Kerala', 'Varkala - Parippally - Madathara Road, 695141', 76.721705, 8.743535),  -- w797655284
  ('Vimala Theatre', 'Thiruvananthapuram', 'Kerala', 'Sivagiri Road, 695145', 76.729013, 8.73337),  -- n1237550819
  ('DDX Cinema', 'Bhopal', 'Madhya Pradesh', 'Kolar Road, 462042', 77.417569, 23.185706),  -- n4781136427
  ('Jyoti Cinema', 'Bhopal', 'Madhya Pradesh', 'Chetak Flyover, 462001', 77.433926, 23.233389),  -- n4782970915
  ('Sangam Cinema', 'Bhopal', 'Madhya Pradesh', 'Bhopal Sanchi Road, Ibrahimpura, 462001', 77.410138, 23.262739),  -- n5049920324
  ('Chandan Cinema', 'Mumbai', 'Maharashtra', 'S Dnyaneshwar Marg, Andheri West, 400058', 72.827077, 19.114739),  -- w203143523
  ('Kalpana Cinema Hall', 'Mumbai', 'Maharashtra', 'Lal Bahadur Shastri Marg, Kurla West, 400070', 72.878992, 19.077455),  -- w353181441
  ('Mehul Cinema', 'Mumbai', 'Maharashtra', 'Pandit Jawaharlal Nehru Road, Mulund West, 400080', 72.949317, 19.165663),  -- w632245751
  ('Metro INOX Cinema', 'Mumbai', 'Maharashtra', 'Mahatma Gandhi Road, Fort, 400020', 72.829068, 18.94288),  -- w46717574
  ('Movietime Cinema', 'Mumbai', 'Maharashtra', 'Ramchandra Extension Lane, Orlem, 400064', 72.834754, 19.189267),  -- w135137729
  ('Strand Cinema', 'Mumbai', 'Maharashtra', 'Bhaskar Rao Kargutkar Marg, A Ward, 400005', 72.829716, 18.918137),  -- w353407534
  ('AM Cinema', 'Nagpur', 'Maharashtra', 'Park, Besa, 440037', 79.09104, 21.085891),  -- n6926124653
  ('Smruti Cinema', 'Nagpur', 'Maharashtra', 'Mount Road, Mominpura, 440001', 79.080591, 21.15834),  -- w444727842
  ('Alankar Cinema', 'Pune', 'Maharashtra', 'Smita Patil Path, Somwar Peth, 411001', 73.876624, 18.528693),  -- w107173244
  ('Liberty Cinema', 'Pune', 'Maharashtra', 'Parade Ground Road, Pune Cantonment, 411001', 73.887568, 18.519376),  -- w52782995
  ('Victory Cinema', 'Pune', 'Maharashtra', 'East Street, Pune Cantonment, 411001', 73.879476, 18.514547),  -- n671453565
  ('Ashok Cinema', 'Thane', 'Maharashtra', 'Station Road, Jambli Naka, 400602', 72.977274, 19.187641),  -- w681516924
  ('Malhar Cinema', 'Thane', 'Maharashtra', 'Gokhale Road, Naupada, 400602', 72.966809, 19.1877),  -- n4663156696
  ('Aarti Cinema', 'Ludhiana', 'Punjab', 'Ferozepur Road Flyover, Gurudev Nagar, 141001', 75.826212, 30.899556),  -- w209205407
  ('Manju Cinema', 'Ludhiana', 'Punjab', 'Sher Shah Suri Marg - GT Road, 141003', 75.866658, 30.89646),  -- n458763223
  ('Raikhy Cinema', 'Ludhiana', 'Punjab', 'Raikhy Cinema Road, 141008', 75.849719, 30.91505),  -- w209212359
  ('Sangeet Cinema', 'Ludhiana', 'Punjab', 'Sangeet Cinema Road, 141003', 75.863753, 30.888476),  -- n1791841244
  ('Shingar Cinema', 'Ludhiana', 'Punjab', 'Shingar Cinema Road, 141009', 75.872482, 30.913287),  -- n2639783065
  ('Suriya Cinema', 'Ludhiana', 'Punjab', 'Churpur Road, Neta G Park, 141027', 75.805916, 30.929364),  -- n1686787875
  ('Cinepolis', 'Jaipur', 'Rajasthan', 'Jhotwara Road, Amba Bari, 302001', 75.771365, 26.941102),  -- n7049512594
  ('Galaxy Cinema', 'Jaipur', 'Rajasthan', 'Shipra Path, 302029', 75.778243, 26.836132),  -- n3078037353
  ('Inox Movies Cinema', 'Jaipur', 'Rajasthan', 'Ajmer Road, Badrawas, 302021', 75.748629, 26.895416),  -- n6938645049
  ('Raj Mandir Cinemas', 'Jaipur', 'Rajasthan', 'Bhagwandas Road, Adarsh Nagar, 302004', 75.810083, 26.915425),  -- n10904794879
  ('Kohinoor Cinema', 'Jodhpur', 'Rajasthan', 'NH25, 342001', 73.005995, 26.280399),  -- n3956812972
  ('Cineworld', 'Glasgow', 'Scotland', 'Barrhead Road, Househillwood, G53 6AG', -4.342993, 55.823098),  -- n3668838706
  ('The Grosvenor Cinema', 'Glasgow', 'Scotland', 'Ashton Lane, West End, G12 8SJ', -4.293176, 55.874666),  -- n437381250
  ('Cosmos Cinema 4K', 'Coimbatore', 'Tamil Nadu', 'Mariamman Koil Street, Ward 56, 641005', 77.002449, 11.013701),  -- w1248293136
  ('Gokulam Cinema', 'Coimbatore', 'Tamil Nadu', 'Coimbatore - Siruvani Road, 641109', 76.801832, 10.959522),  -- w497332987
  ('KG Big Cinema', 'Coimbatore', 'Tamil Nadu', 'Arts College Campus Road, Ward 73, 641001', 76.972126, 10.999956),  -- w191231187
  ('Krithika Cinema Hall', 'Coimbatore', 'Tamil Nadu', 'Thadagam Road, Ward 8, 641001', 76.927408, 11.034218),  -- w461696407
  ('Lakshmi Cinema Hall', 'Coimbatore', 'Tamil Nadu', 'Mettupalayam Road, 641047', 76.951943, 11.170047),  -- n1470928245
  ('Bhelec Cinema', 'Tiruchirappalli', 'Tamil Nadu', 'NH83, Kailasapuram, 620022', 78.797371, 10.769441),  -- w1535747126
  ('LA Cinema', 'Tiruchirappalli', 'Tamil Nadu', 'Musiri - Thuraiyur - Attur Road, 621010', 78.593742, 11.150727),  -- n12287751625
  ('Flanker Cinema', 'Hyderabad', 'Telangana', 'Langar House road, Langar Hauz, 500048', 78.419991, 17.376392),  -- n3390852922
  ('INOX Cinema', 'Hyderabad', 'Telangana', 'Road Number 1, Banjara Hills, 500034', 78.448401, 17.418803),  -- n890312487
  ('Manju Cinema', 'Hyderabad', 'Telangana', 'Sarojini Devi Road, Ward 148 Ramgopalpet, 500003', 78.493572, 17.440473),  -- n582824598
  ('Shanti Cinema Theatre', 'Hyderabad', 'Telangana', 'Narayanguda Main Road, Narayanguda, 500027', 78.489877, 17.396165),  -- w142300260
  ('Tarakarama Cinema', 'Hyderabad', 'Telangana', 'Chowdary Bagh, Ward 78 Gunfoundry, 500095', 78.487931, 17.390484),  -- w357130502
  ('Panna Palace Cinema', 'Agra', 'Uttar Pradesh', 'Shamsabad Road, Taj Ganj, 282001', 78.046072, 27.141746),  -- n12647728169
  ('Rajeev Cinema', 'Agra', 'Uttar Pradesh', 'Gyas Pura, Shahganj, 282010', 77.982142, 27.173864),  -- w303616715
  ('Sheila Cinema Hall', 'Agra', 'Uttar Pradesh', 'Surya Nagar Road, Civil Lines, 282005', 77.998882, 27.207083),  -- n3102600281
  ('Tulsi Cinema', 'Agra', 'Uttar Pradesh', 'Flyover, Civil Lines, 282005', 78.00096, 27.211095),  -- n3102616857
  ('Galaxie Cinema', 'Ghaziabad', 'Uttar Pradesh', 'Madan Mohan Malviya Marg, Vaishali, 201001', 77.345162, 28.655041),  -- n2069359469
  ('New Vasanth Cinema', 'Kanpur', 'Uttar Pradesh', 'Kalpi Road, 208012', 80.31325, 26.46542),  -- n1291412679
  ('Carnival Cinema', 'Lucknow', 'Uttar Pradesh', 'Kanpur-Lucknow Road, Alambagh, 226005', 80.904764, 26.814826),  -- n5372681324
  ('Novelty Cinema Aliganj', 'Lucknow', 'Uttar Pradesh', 'Kursi Road, Hasan Ganj, 226006', 80.946383, 26.881849),  -- n4186033390
  ('Shubham Cinema', 'Lucknow', 'Uttar Pradesh', 'Capper Road, Aminabad, 226018', 80.933588, 26.847831),  -- w1461484957
  ('Sudharshan Cinema', 'Lucknow', 'Uttar Pradesh', 'Subhash Marg, Arya Nagar, 226004', 80.921185, 26.833332),  -- n14202561801
  ('Anurag Cinema', 'Meerut', 'Uttar Pradesh', 'Baghpat Road, 250001', 77.689967, 28.973809),  -- w1302811937
  ('BLW Cinema Hall', 'Varanasi', 'Uttar Pradesh', 'SH74, Bhullanpur, 221009', 82.961894, 25.286256),  -- n292730020
  ('Carnival Cinema PDR', 'Varanasi', 'Uttar Pradesh', 'Luxa Road, 221001', 83.002923, 25.310062),  -- n8848598037
  ('IP Vijaya Mall Cinema Hall', 'Varanasi', 'Uttar Pradesh', 'Durgakund Road, 222005', 83.001028, 25.297533),  -- n11697410090
  ('Sajjan Cinema', 'Varanasi', 'Uttar Pradesh', 'Vidyapeeth Lanka Road, Cantonment, 221001', 82.988701, 25.315876),  -- n8844949735
  ('Natraj Cinema', 'Dehradun', 'Uttarakhand', 'Chakrata Road, 248001', 78.035638, 30.326483),  -- n13247612788
  ('Orient Cinema', 'Dehradun', 'Uttarakhand', 'Ugrasain Road, 248001', 78.046435, 30.327545),  -- n1705583059
  ('Payal Cinema', 'Dehradun', 'Uttarakhand', 'Neshvilla Road, 248001', 78.044609, 30.328051),  -- n1710188691
  ('Ritz Cinema', 'Dehradun', 'Uttarakhand', 'Mall Road, Picture Palace, 248179', 78.067142, 30.459826),  -- n5001731238
  ('Bijoli Cinema Hall', 'Kolkata', 'West Bengal', 'Rupchand Mukherjee Lane, Bhowanipore, 700025', 88.345696, 22.526878),  -- w239402258
  ('Kalika Cinema', 'Kolkata', 'West Bengal', 'Nibaran Banerjee Road, Hazra, 700026', 88.346011, 22.522606),  -- w175776604
  ('Moonlight Cinema', 'Kolkata', 'West Bengal', 'Armenian Street, Tiretta, 700073', 88.359831, 22.577542),  -- w525517318
  ('Paradise Cinema', 'Kolkata', 'West Bengal', 'Crooked Lane, Esplanade, 700069', 88.351883, 22.566769),  -- n4368104697
  ('Priya Cinema', 'Kolkata', 'West Bengal', 'Rash Behari Avenue, Manoharpukur, 700029', 88.354393, 22.517988),  -- w187711647
  ('Ujjala Cinema', 'Kolkata', 'West Bengal', 'Halder Para Lane, Hazra, 700026', 88.346212, 22.521069),  -- w175777378
  ('Vishal Cinema', 'Siliguri', 'West Bengal', 'Sevoke Road, 734001', 88.431843, 26.73246)  -- n14067300691
) AS v(name, city, state, address, lon, lat);

-- --------------------------------------------- the screens that exist now

-- The four screens from V3 have bookings and holds against them, so they
-- cannot be deleted and reseeded. They become Cinepolis Jaipur: a real
-- multiplex, in the right city, where four screens is plausible, and V12
-- already made the fourth of them the luxury hall.
--
-- This runs before the generated screens below, so the NOT EXISTS there
-- skips Cinepolis and it does not end up with seven.
UPDATE screen
   SET cinema_id = (SELECT id FROM cinema WHERE name = 'Cinepolis' AND city = 'Jaipur')
 WHERE cinema_id IS NULL;

ALTER TABLE screen ALTER COLUMN cinema_id SET NOT NULL;

-- ------------------------------------------------------ generated screens

-- Screen count and layout are a deterministic function of the cinema, not
-- random, so this migration produces the same database every time it runs.
--
-- Chains get three screens, independents one or two, and Raj Mandir gets
-- exactly one because it is a famous single-screen hall and modelling it as
-- a multiplex would be wrong about the one cinema in this list that anybody
-- reading it will recognise.
--
-- The layout is the point of screen.aisle_after. Three room shapes, picked
-- by a cheap hash so the set is varied rather than uniform:
--   {5}     8 rows of 10, one aisle down the middle
--   {3,9}   10 rows of 12, the 3 | 6 | 3 room the frontend hardcodes today
--   {4,12}  14 rows of 16, a large hall
INSERT INTO screen (cinema_id, name, hall_class, aisle_after)
SELECT c.id,
       CASE WHEN p.screens = 1 THEN 'Main Hall' ELSE 'Screen ' || n.n END,
       CASE WHEN c.name = 'Raj Mandir Cinemas'  THEN 'LUXURY'
            WHEN p.chain AND n.n = p.screens     THEN 'LUXURY'
            ELSE 'STANDARD' END,
       CASE WHEN c.name = 'Raj Mandir Cinemas' THEN '{4,12}'::int[]
            WHEN (c.id + n.n) % 3 = 0          THEN '{5}'::int[]
            WHEN (c.id + n.n) % 3 = 1          THEN '{3,9}'::int[]
            ELSE                                    '{4,12}'::int[]
       END
FROM cinema c
CROSS JOIN LATERAL (
    SELECT c.name ~* '(PVR|INOX|Cinepolis|Cineworld|Odeon|Vue|Everyman|Picturehouse|Omniplex|Carnival|Movietime|Adlabs|Multiplex|IMAX)'
           AS chain
) k
CROSS JOIN LATERAL (
    SELECT k.chain,
           CASE WHEN c.name = 'Raj Mandir Cinemas' THEN 1
                WHEN k.chain                       THEN 3
                ELSE 1 + (c.id % 2) END AS screens
) p
CROSS JOIN LATERAL generate_series(1, p.screens) AS n(n)
WHERE NOT EXISTS (SELECT 1 FROM screen s WHERE s.cinema_id = c.id);
-- -------------------------------------------------------- generated seats

-- The room's dimensions are read back off aisle_after rather than carried in
-- a second pair of columns. Storing rows and seats_per_row as well would be
-- a second source of truth about a room that the seat rows already describe
-- exactly, which is the mistake V12 removed when it dropped
-- showtime.price_minor.
--
-- Tier bands are proportional rather than fixed letters: the front half of
-- the house is Normal, the next 30% Premium, the back 20% Executive. On a
-- ten row screen that is A-E, F-H, I-J, which is exactly what V6 hardcoded,
-- so the existing rooms are unchanged and a 14 row room gets the same shape
-- rather than running out of tiers.
INSERT INTO seat (screen_id, row_label, seat_number, tier)
SELECT sc.id,
       chr(64 + r.n),
       col.n,
       CASE WHEN r.n <= d.n_rows * 0.5 THEN 'NORMAL'
            WHEN r.n <= d.n_rows * 0.8 THEN 'PREMIUM'
            ELSE 'EXECUTIVE' END
FROM screen sc
CROSS JOIN LATERAL (
    SELECT CASE WHEN sc.aisle_after = '{5}'::int[]   THEN 8
                WHEN sc.aisle_after = '{3,9}'::int[] THEN 10
                ELSE 14 END AS n_rows,
           CASE WHEN sc.aisle_after = '{5}'::int[]   THEN 10
                WHEN sc.aisle_after = '{3,9}'::int[] THEN 12
                ELSE 16 END AS n_cols
) d
CROSS JOIN LATERAL generate_series(1, d.n_rows) AS r(n)
CROSS JOIN LATERAL generate_series(1, d.n_cols) AS col(n)
WHERE NOT EXISTS (SELECT 1 FROM seat s WHERE s.screen_id = sc.id);

-- ------------------------------------------- the view learns about money

-- Rebuilt for one reason: the join to hall_class_tier_price has to filter on
-- currency. Without it, a second currency makes every seat match two price
-- rows and appear in the view twice, which is exactly what happened when V17
-- first tried to insert the GBP ladder.
--
-- currency now comes from the cinema rather than from showtime.currency. The
-- showtime column is left in place for now because the Showtime entity still
-- maps it and ddl-auto is validate, but nothing reads it after this and it
-- goes in a later migration alongside that field.
--
-- Column list is unchanged, so SeatMapQuery needs no edit.
DROP VIEW seat_availability;

CREATE VIEW seat_availability AS
SELECT st.id         AS showtime_id,
       s.id          AS seat_id,
       s.row_label   AS row_label,
       s.seat_number AS seat_number,
       CASE
         WHEN EXISTS (SELECT 1
                      FROM booking_seat bs
                      WHERE bs.showtime_id = st.id
                        AND bs.seat_id     = s.id)
           THEN 'BOOKED'
         WHEN EXISTS (SELECT 1
                      FROM hold_seat hs
                      JOIN seat_hold h ON h.id = hs.hold_id
                      WHERE hs.showtime_id = st.id
                        AND hs.seat_id     = s.id
                        AND h.booked_at    IS NULL
                        AND h.released_at  IS NULL
                        AND h.expires_at   > now())
           THEN 'HELD'
         ELSE 'AVAILABLE'
       END           AS status,
       s.tier          AS tier,
       t.display_name  AS tier_name,
       sc.hall_class   AS hall_class,
       p.price_minor   AS base_price_minor,
       ci.currency     AS currency
FROM showtime st
JOIN screen    sc ON sc.id        = st.screen_id
JOIN cinema    ci ON ci.id        = sc.cinema_id
JOIN seat      s  ON s.screen_id  = st.screen_id
JOIN seat_tier t  ON t.code       = s.tier
JOIN hall_class_tier_price p
                  ON p.currency   = ci.currency
                 AND p.hall_class = sc.hall_class
                 AND p.tier       = s.tier;

-- ------------------------------------------------------- the GBP ladder

-- Safe now, and only now, because the view above filters on currency.
--
-- Pence. Around nine pounds for a standard seat, up to twenty five for an
-- executive recliner in a premium hall, which is roughly what a UK chain
-- charges. The UK ladder is flatter than the Indian one, 1.8x bottom to top
-- against 2.5x, and that is what the two markets look like rather than an
-- oversight.
--
-- Every figure is a whole number of pounds, so with bands that are whole
-- multiples of 5% every adjustment lands on a whole multiple of five pence
-- and the rounding rule in PricingRules never fires. Same property the INR
-- ladder was built for in V12.
INSERT INTO hall_class_tier_price (currency, hall_class, tier, price_minor) VALUES
    ('GBP', 'STANDARD', 'NORMAL',     900),
    ('GBP', 'STANDARD', 'PREMIUM',   1300),
    ('GBP', 'STANDARD', 'EXECUTIVE', 1600),
    ('GBP', 'LUXURY',   'NORMAL',    1400),
    ('GBP', 'LUXURY',   'PREMIUM',   1900),
    ('GBP', 'LUXURY',   'EXECUTIVE', 2500);
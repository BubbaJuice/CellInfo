import csv
import os
import time
from collections import defaultdict

def get_lte_band_from_earfcn(earfcn):
    earfcn = int(earfcn)
    bands = [
        (0, 599, 1), (600, 1199, 2), (1200, 1949, 3), (1950, 2399, 4),
        (2400, 2649, 5), (2650, 2749, 6), (2750, 3449, 7), (3450, 3799, 8),
        (3800, 4149, 9), (4150, 4749, 10), (4750, 4949, 11), (5010, 5179, 12),
        (5180, 5279, 13), (5280, 5379, 14), (5730, 5849, 17), (5850, 5999, 18),
        (6000, 6149, 19), (6150, 6449, 20), (6450, 6599, 21), (6600, 7399, 22),
        (7500, 7699, 23), (7700, 8039, 24), (8040, 8689, 25), (8690, 9039, 26),
        (9040, 9209, 27), (9210, 9659, 28), (9660, 9769, 29), (9770, 9869, 30),
        (9870, 9919, 31), (9920, 10359, 32), (36000, 36199, 33), (36200, 36349, 34),
        (36350, 36949, 35), (36950, 37549, 36), (37550, 37749, 37), (37750, 38249, 38),
        (38250, 38649, 39), (38650, 39649, 40), (39650, 41589, 41), (41590, 43589, 42),
        (43590, 45589, 43), (45590, 46589, 44), (46590, 46789, 45), (46790, 54539, 46),
        (54540, 55239, 47), (55240, 56739, 48), (56740, 58239, 49), (58240, 59089, 50),
        (59090, 59139, 51), (59140, 60139, 52), (60140, 60254, 53), (60255, 60304, 54),
        (65536, 66435, 65), (66436, 67335, 66), (67336, 67535, 67), (67536, 67835, 68),
        (67836, 68335, 69), (68336, 68585, 70), (68586, 68935, 71), (68936, 68985, 72),
        (68986, 69035, 73), (69036, 69465, 74), (69466, 70315, 75), (70316, 70365, 76),
        (70366, 70545, 85), (70546, 70595, 87), (70596, 70645, 88), (70646, 70655, 103),
        (70656, 70705, 106), (70656, 71055, 107), (71056, 73335, 108)
    ]
    
    for start, end, band in bands:
        if start <= earfcn <= end:
            return band
    return -1  # Unknown or unsupported band


def process_csv(input_file, output_file):
    cell_data = defaultdict(lambda: {
        'lat': [],
        'lon': [],
        'rsrp': [],
        'mcc': '',
        'mnc': '',
        'tac': '',
        'cellid': '',
        'earfcn': '',
        'pci': '',
        'band': ''
    })
    unix_timestamp = int(os.path.getmtime(input_file))
    
    with open(input_file, 'r') as csvfile:
        reader = csv.reader(csvfile)
        for row in reader:
            if 'NR' in row:
                continue
            
            lat, lon, _, mcc, mnc, tac, cellid, rsrp, data_type, subtype, earfcn, pci = row
            
            if data_type != 'LTE':
                continue
            
            band = get_lte_band_from_earfcn(earfcn)
            key = f"{cellid}_{band}"
            
            cell_data[key]['lat'].append(float(lat))
            cell_data[key]['lon'].append(float(lon))
            cell_data[key]['rsrp'].append(int(rsrp))
            cell_data[key]['mcc'] = mcc
            cell_data[key]['mnc'] = mnc
            cell_data[key]['tac'] = tac
            cell_data[key]['cellid'] = cellid
            cell_data[key]['earfcn'] = earfcn
            cell_data[key]['pci'] = pci
            cell_data[key]['band'] = band
    
    with open(output_file, 'w', newline='') as csvfile:
        writer = csv.writer(csvfile)
        writer.writerow(['cellId', 'type', 'timestamp', 'enbId', 'earfcn', 'pci', 'cellSector', 'bandNumber', 'tac', 'mcc', 'mnc', 'operator', 'rsrp', 'latitude', 'longitude', 'bestRsrp', 'bestLatitude', 'bestLongitude', 'seen'])
        
        for key, data in cell_data.items():
            cellId = data['cellid']
            enbId = str(int(cellId) // 256)
            earfcn = data['earfcn']
            cellSector = str(int(cellId) % 256)
            bandNumber = str(data['band'])
            tac = data['tac']
            mcc = data['mcc']
            mnc = data['mnc']
            pci = data['pci']
            operator = ''
            
            valid_rsrps = [rsrp for rsrp in data['rsrp'] if rsrp != -44]
            if valid_rsrps:
                best_rsrp = max(valid_rsrps)
                best_index = data['rsrp'].index(best_rsrp)
                best_lat = data['lat'][best_index]
                best_lon = data['lon'][best_index]
            else:
                best_rsrp = None
                best_lat = ''
                best_lon = ''
            
            writer.writerow([
                cellId, 'LTE', unix_timestamp, enbId, earfcn, pci, cellSector, bandNumber, tac, mcc, mnc, operator,
                int(best_rsrp) if best_rsrp else '',
                f"{best_lat:.7f}" if best_lat else '', 
                f"{best_lon:.7f}" if best_lon else '', 
                best_rsrp if best_rsrp else '', 
                f"{best_lat:.7f}" if best_lat else '', 
                f"{best_lon:.7f}" if best_lon else '', 
                'true'
            ])

def main():
    # Read input filenames from console input
    print("Paste the list of input files (one per line), then press Ctrl+D (Unix) or Ctrl+Z (Windows) followed by Enter:")
    input_files = []
    while True:
        try:
            line = input()
            if line:
                input_files.append(line.strip())
        except EOFError:
            break

    # Create output directory if it doesn't exist
    output_dir = os.path.join(os.path.expanduser('~'), 'Downloads', 'processed_files')
    os.makedirs(output_dir, exist_ok=True)

    for input_file in input_files:
        # Generate output filename
        base_name = os.path.basename(input_file)
        output_file = os.path.join(output_dir, f'processed_{base_name}')

        print(f"Processing {input_file}...")
        process_csv(input_file, output_file)
        print(f"Finished processing {input_file}. Output written to {output_file}")

if __name__ == "__main__":
    main()
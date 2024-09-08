import csv
from datetime import datetime
import os
import shlex

def parse_csv(filename):
    data = {}
    with open(filename, 'r') as file:
        reader = csv.DictReader(file)
        if not reader.fieldnames:
            raise ValueError(f"The file {filename} appears to be empty.")
        
        print(f"Columns in {filename}: {reader.fieldnames}")
        
        required_columns = ['cellId', 'bandNumber']
        missing_columns = [col for col in required_columns if col not in reader.fieldnames]
        if missing_columns:
            raise ValueError(f"The file {filename} is missing the following required columns: {missing_columns}. Available columns are: {reader.fieldnames}")
        
        for row in reader:
            key = (row['cellId'], row['bandNumber'])
            data[key] = row
    return data

def merge_csvs(csv_a, csv_b):
    for key, cell_b in csv_b.items():
        if key in csv_a:
            cell_a = csv_a[key]
            
            time_a = datetime.fromtimestamp(int(cell_a['timestamp']) / 1000)
            time_b = datetime.fromtimestamp(int(cell_b['timestamp']) / 1000)
            
            if time_b > time_a:
                cell_a.update({
                    'timestamp': cell_b['timestamp'],
                    'rsrp': cell_b['rsrp'],
                    'latitude': cell_b['latitude'],
                    'longitude': cell_b['longitude']
                })
            
            # Safely convert 'bestRsrp' to float, handling empty strings
            best_rsrp_a = float(cell_a['bestRsrp']) if cell_a['bestRsrp'] else float('-inf')
            best_rsrp_b = float(cell_b['bestRsrp']) if cell_b['bestRsrp'] else float('-inf')
            
            if best_rsrp_b > best_rsrp_a:
                cell_a.update({
                    'bestRsrp': cell_b['bestRsrp'],
                    'bestLatitude': cell_b['bestLatitude'],
                    'bestLongitude': cell_b['bestLongitude']
                })
            
            if cell_b['seen'].lower() == 'true' or cell_a['seen'].lower() == 'true':
                cell_a['seen'] = 'true'
        else:
            csv_a[key] = cell_b
    
    return csv_a

def write_csv(data, filename):
    if not data:
        print(f"No data to write to {filename}. Skipping file creation.")
        return
    
    try:
        with open(filename, 'w', newline='') as file:
            fieldnames = list(next(iter(data.values())).keys())
            writer = csv.DictWriter(file, fieldnames=fieldnames)
            writer.writeheader()
            for row in data.values():
                writer.writerow(row)
        print(f"Successfully wrote merged data to {filename}")
    except PermissionError:
        print(f"Error: Permission denied when trying to write to {filename}")
        print("Please make sure you have write permissions for this location.")
        print("Try specifying a different output location, such as your Desktop or Documents folder.")
    except Exception as e:
        print(f"An error occurred while writing to {filename}: {str(e)}")

def main():
    print("Enter the file paths (one per line). You can use quotes for paths with spaces. Press Enter twice when done:")
    input_files = []
    while True:
        file_path = input().strip()
        if file_path:
            # Remove outer quotes if present
            file_path = file_path.strip('"')
            input_files.append(file_path)
        else:
            break

    if not input_files:
        print("No input files provided. Exiting.")
        return

    while True:
        output_file = input("Enter the output file path (including filename.csv): ").strip()
        # Remove outer quotes if present
        output_file = output_file.strip('"')
        
        if not output_file.endswith('.csv'):
            print("The output file should have a .csv extension. Please try again.")
            continue
        
        output_dir = os.path.dirname(output_file)
        if not output_dir:
            output_file = os.path.join(os.getcwd(), output_file)
        
        if not os.path.exists(output_dir):
            try:
                os.makedirs(output_dir)
            except PermissionError:
                print(f"Error: Unable to create directory {output_dir}. Please choose a different location.")
                continue
        
        if os.path.exists(output_file):
            overwrite = input(f"{output_file} already exists. Do you want to overwrite it? (y/n): ").lower()
            if overwrite != 'y':
                continue
        
        break

    merged_data = {}
    for file in input_files:
        print(f"Processing file: {file}")
        try:
            csv_data = parse_csv(file)
            merged_data = merge_csvs(merged_data, csv_data)
        except Exception as e:
            print(f"Error processing {file}: {str(e)}")
            print("Skipping this file and continuing with the next...")

    write_csv(merged_data, output_file)

if __name__ == "__main__":
    main()
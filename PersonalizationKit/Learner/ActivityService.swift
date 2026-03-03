//
//  ActivityService.swift
//  namaz
//
//  Created by Nursultan Askarbekuly on 09.08.2023.
//  Copyright © 2023 Nursultan Askarbekuly. All rights reserved.
//

import Foundation

enum ServiceError: Error {
    case missingInput
    case missingToken
    case failedURLInitialization
    case encodingFailed
    case failedResponseInitialization
    case requestFailed
    case decodingFailed
}


extension ServiceError: LocalizedError {
    var errorDescription: String? {
        switch self {
        case .missingInput:
            return NSLocalizedString("Input data is missing or invalid.", comment: "Missing Input")
        case .missingToken:
            return NSLocalizedString("The required token is missing.", comment: "Missing Token")
        case .failedURLInitialization:
            return NSLocalizedString("Failed to initialize the URL.", comment: "Failed URL Initialization")
        case .encodingFailed:
            return NSLocalizedString("Failed to encode the data.", comment: "Encoding Failed")
        case .failedResponseInitialization:
            return NSLocalizedString("Failed to initialize the response.", comment: "Failed Response Initialization")
        case .requestFailed:
            return NSLocalizedString("The network request failed.", comment: "Request Failed")
        case .decodingFailed:
            return NSLocalizedString("Failed to decode the response data.", comment: "Decoding Failed")
        }
    }
}

@available(iOS 10.0, *)
public class ActivityService {
    
    public static var shared = ActivityService()
    
    /// Access to activity history must be serialized.
    /// This service is called from UI + async Task contexts; unsynchronized mutations can crash at runtime.
    private let historyQueue = DispatchQueue(label: "PersonalizationKit.ActivityService.historyQueue", qos: .utility)
    private var _localActivityHistory: [ActivityLog]?
    
    public var localActivityHistory: [ActivityLog]? {
        get { historyQueue.sync { _localActivityHistory } }
        set {
            historyQueue.sync { _localActivityHistory = newValue }
            historyQueue.async { [weak self] in
                self?.saveLocalHistory(snapshot: newValue)
            }
        }
    }
    
    private lazy var analyticsUrl = "\(StorageDelegate.learnerStorage.serverUrl)/analytics/\(StorageDelegate.learnerStorage.activtyLogCollectionName)"
    private let userDefaultsKey = "engagement_history"

    /// File URL for engagement history (moved out of UserDefaults to avoid 4MB limit).
    private lazy var historyFileURL: URL? = {
        let fm = FileManager.default
        guard let appSupport = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else {
            return nil
        }
        let dir = appSupport.appendingPathComponent("PersonalizationKit", isDirectory: true)
        if !fm.fileExists(atPath: dir.path) {
            try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        }
        return dir.appendingPathComponent("engagement_history.json")
    }()
    
    public func kickstartActivityService() {
        
        guard let localHistory = retrieveLocalHistory() else {
            print(#function, "error getting local history")
            historyQueue.sync { _localActivityHistory = [] }
            return
        }
        
        historyQueue.sync { _localActivityHistory = localHistory }
#if DEBUG
        print("localActivityHistory:", localHistory.count)
#endif
    }
    
    @available(iOS 13.0, *)
    public func bulkUploadActivitiesToRemote() {
        Task {
            do {
                let remotelyAddedActivityLogs = try await logActivitiesToRemoteHistory(minActivitiesToLogCount: 1)
                print("successfully logged activities to remote storage:", remotelyAddedActivityLogs.count)
            } catch {
                print(#function, "error logging activities to remote storage:", error.localizedDescription)
            }
        }
    }
    
    public func logActivityToHistory(_ activityLog: ActivityLog) {
        var didAppend = false
        var snapshotToSave: [ActivityLog]?
        
        historyQueue.sync {
            if _localActivityHistory == nil { _localActivityHistory = [] }
            guard _localActivityHistory?.contains(where: { $0.id == activityLog.id }) == false else {
                return
            }
            _localActivityHistory?.append(activityLog)
            didAppend = true
            snapshotToSave = _localActivityHistory
        }
        
        guard didAppend else {
            print("Error adding history log: either the history is nil or item has been previously added. Local history:", localActivityHistory ?? "nil")
            return
        }
        
        historyQueue.async { [weak self] in
            self?.saveLocalHistory(snapshot: snapshotToSave)
        }
        
        if #available(iOS 13.0, *) {
            Task {
                do {
                    try await self.logSingleActivitiesToRemoteHistory(activityLog)
                    StorageDelegate.learnerStorage.store(true, forKey: "\(activityLog.id)")
                } catch {
                    print("failed to log a single activity: ", error.localizedDescription)
                }
            }
        }
    }
    
    @available(iOS 13.0.0, *)
    public func logActivitiesToRemoteHistory(minActivitiesToLogCount: Int = 100) async throws -> [ActivityLog] {
        
        var activitiesToBeLogged: [ActivityLog] = []
        
        let historySnapshot = historyQueue.sync { _localActivityHistory } ?? []
        
        for localLog in historySnapshot.sorted(by: { $0.startDate ?? Date() < $1.startDate ?? Date() }) {
            
            if StorageDelegate.learnerStorage.retrieve(forKey: "\(localLog.id)") as? Bool ?? false {
                /// skipping item as it was already marked as reported
                continue
            }
            
            activitiesToBeLogged.append(localLog)
            
            if activitiesToBeLogged.count > 500 {
                /// we report up to 500 logs at a time
                break
            }
        }
        
        
        if activitiesToBeLogged.count < minActivitiesToLogCount {
            /// too few new logs, no need to upload yet.
            throw ServiceError.missingInput
        }
        
        /// add to remote history
        guard let url = URL(string: analyticsUrl+"/bulk") else {
            throw ServiceError.failedURLInitialization
        }
        
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.addValue("application/json", forHTTPHeaderField: "Content-Type")
        
        do {
            let requestBody = try JSONEncoder().encode(activitiesToBeLogged)
            request.httpBody = requestBody
        } catch {
#if DEBUG
            print("Failed to encode the activityLog:", error.localizedDescription)
#endif
        }
        
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let httpResponse = response as? HTTPURLResponse,
                  (200...299).contains(httpResponse.statusCode) else {
                print(#function, "Failed with response: \( (response as? HTTPURLResponse)?.statusCode ?? 0 )") //, String(data: data, encoding: .utf8) ?? "")
                throw ServiceError.requestFailed
            }
            guard let activityLogs = try? JSONDecoder().decode([ActivityLog].self, from: data) else {
                print(#function, "Failed to decode", String(data: data, encoding: .utf8) ?? "")
                throw ServiceError.decodingFailed
            }
            
            for log in activityLogs {
                StorageDelegate.learnerStorage.store(true, forKey: "\(log.id)")
            }
#if DEBUG
            print(#function, "activities logged to remote history:", activityLogs.count)
#endif
            
            return activityLogs
        } catch {
            // Handle other errors
            throw error
        }
        
    }
    
    @available(iOS 13.0.0, *)
    @discardableResult
    public func logSingleActivitiesToRemoteHistory(_ localActivity: ActivityLog) async throws -> ActivityLog {
        
        /// add to remote history
        guard let url = URL(string: analyticsUrl) else {
            throw ServiceError.failedURLInitialization
        }
        
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.addValue("application/json", forHTTPHeaderField: "Content-Type")
        
        let encoder = JSONEncoder()
        do {
            let jsonData = try encoder.encode(localActivity)
            request.httpBody = jsonData
        } catch {
            throw ServiceError.encodingFailed
        }
        
        let (data, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 else {
            throw ServiceError.requestFailed
        }
        
        do {
            let decoder = JSONDecoder()
            let remoteActivity = try decoder.decode(ActivityLog.self, from: data)
            return remoteActivity
        } catch {
            throw ServiceError.decodingFailed
        }
    }
    
    
    private func saveLocalHistory(snapshot: [ActivityLog]?) {
        guard let localActivitiesHistory = snapshot else {
            print(#function, "Error localActivitiesHistory is nil")
            return
        }

        guard let fileURL = historyFileURL else {
            print(#function, "Error: could not determine history file URL")
            return
        }

        let encoder = JSONEncoder()

        do {
            let data = try encoder.encode(localActivitiesHistory)
            try data.write(to: fileURL, options: .atomic)
        } catch {
            print(#function, "Error encoding/writing localActivitiesHistory: \(error)")
        }
    }
    
    private func retrieveLocalHistory() -> [ActivityLog]? {
        let decoder = JSONDecoder()

        // Try file-based storage first
        if let fileURL = historyFileURL,
           let data = try? Data(contentsOf: fileURL),
           let history = try? decoder.decode([ActivityLog].self, from: data) {
            return history
        }

        // Fall back to UserDefaults (one-time migration from old storage)
        guard let legacyData = StorageDelegate.learnerStorage.retrieve(forKey: userDefaultsKey) as? Data else {
            return nil
        }

        do {
            let history = try decoder.decode([ActivityLog].self, from: legacyData)
            // Migrate to file-based storage and free UserDefaults
            if let fileURL = historyFileURL {
                try? legacyData.write(to: fileURL, options: .atomic)
                StorageDelegate.learnerStorage.remove(forKey: userDefaultsKey)
                print("Migrated engagement_history (\(legacyData.count / 1024)KB) from UserDefaults to file storage")
            }
            return history
        } catch {
            print(#function, "Error decoding local history: \(error)")
            return nil
        }
    }
    
    public enum ValueLogic {
        case max
        case min
        case first
        case last
    }
    
    public func getActivity(activityId: String, type: String? = nil, value: String? = nil, logic: ValueLogic = .max) -> ActivityLog? {
        let localActivityHistory = historyQueue.sync { _localActivityHistory }
        guard let localActivityHistory else {
            return nil
        }
        
        // Filter activity logs
        let activityLogs = localActivityHistory.filter {
            $0.activityId == activityId && (type == nil || $0.type == type) && (value == nil || $0.value == value)
        }
        
        if !activityLogs.isEmpty {
            // Safely handle logic
            switch logic {
            case .first:
                return activityLogs.first
            case .last:
                return activityLogs.last
            case .max:
                return activityLogs.max { (Decimal(string: $0.value ?? "") ?? 0) < Decimal(string: $1.value ?? "") ?? 0 }
            case .min:
                return activityLogs.min { (Decimal(string: $0.value ?? "") ?? 0) < Decimal(string: $1.value ?? "") ?? 0 }
            }
        }
        
        return nil
    }
    
    public func getActivities(of types: [String]) -> [ActivityLog]? {
        let localActivityHistory = historyQueue.sync { _localActivityHistory }
        guard let localActivityHistory else {
            return nil
        }
        
        return localActivityHistory.filter({ types.contains($0.type) })
    }
    
    public func getAllInstances(_ activityId: String? = nil, type: String? = nil, value: String? = nil) -> [ActivityLog] {
        let localActivityHistorySnapshot = historyQueue.sync { _localActivityHistory }
        guard var localActivityHistory = localActivityHistorySnapshot else {
            return []
        }
        
        if let activityId = activityId {
            localActivityHistory = localActivityHistory.filter({ $0.activityId == activityId })
        }
        
        if let type = type {
            localActivityHistory = localActivityHistory.filter({ $0.type == type })
        }
        
        if let value = value {
            localActivityHistory = localActivityHistory.filter({ $0.value == value })
        }
        
        return localActivityHistory
    }
    
    /// Returns a merged profile of learner.properties + [activityId: maxValue]
    public func getSummary() -> [String: String] {
        
        let localActivityHistory = historyQueue.sync { _localActivityHistory }
        guard let localActivityHistory else {
            return [:]
        }
        
        // Group logs by activityId
        let grouped = Dictionary(grouping: localActivityHistory, by: \.activityId)
        
        // For each group, pick the max value
        let maxValuesByActivityId: [String:String] = grouped.compactMapValues { logs in
            // 1) Try numeric comparison
            let numericValues = logs.compactMap { log in
                log.value.flatMap(Double.init)
            }
            if let maxNum = numericValues.max() {
                return String(maxNum)
            }
            // 2) Fallback to lexicographical string max
            return logs.compactMap { $0.value }.max()
        }
        
        return maxValuesByActivityId
    }
    
}

//
//  ActivityLog.swift
//  namaz
//
//  Created by Nursultan Askarbekuly on 09.08.2023.
//  Copyright © 2023 Nursultan Askarbekuly. All rights reserved.
//

import Foundation

@available(iOS 10, *)
public struct ActivityLog: Codable, Identifiable {
    public let id: UUID
    var learnerId: UUID
    
    public let type: String
    public let activityId: String
    public let value: String?
    
    let startDateString: String?
    let completionDateString: String?
    let buildVersion: String?
    let sessionNumber: Int?
    
    var startDate: Date? {
        guard let startDateString = startDateString else {
            return nil
        }
        return startDateString.isoStringToDate()
    }
    
    public var completionDate: Date? {
        guard let completionDateString = completionDateString else {
            return nil
        }
        return completionDateString.isoStringToDate()
    }
    
    enum CodingKeys: String, CodingKey {
        case id
        case learnerId = "learner_id"
        case activityId = "activity_id"
        case type
        case value
        case startDateString = "start_date"
        case completionDateString = "completion_date"
        case buildVersion = "build_version"
        case sessionNumber = "session_number"
    }
    
    
    init?(activityId: String,
          type: String,
          value: String?,
          startDate: Date,
          completionDate: Date = Date(),
          buildVersion: String?,
          sessionNumber: Int?
    ){
            
        let dateFormatter: DateFormatter = {
            let dateFormatter = DateFormatter()
            dateFormatter.dateFormat = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSZ"
            return dateFormatter
        }()

        
        guard let learnerId = LearnerService.shared.learnerId else {
            print(#function, "error: no learner id")
            return nil
        }
        
        let id = UUID()
        let startDateString = dateFormatter.string(from: startDate)
        let completionDateString = dateFormatter.string(from: completionDate)
        
        self.id = id
        self.learnerId = learnerId
        self.activityId = activityId
        self.type = type
        self.value = value
        self.startDateString = startDateString
        self.completionDateString = completionDateString
        self.buildVersion = buildVersion
        self.sessionNumber = sessionNumber
    }

}



extension String {
    
    func isoStringToDate() -> Date? {
        
        let dateFormatter = DateFormatter()
        
        dateFormatter.dateFormat = "yyyy-MM-dd'T'HH:mm:ssZ"
        
        if let date = dateFormatter.date(from: self) {
            return date
        } else {
            var decimalPointCount = 10
            while decimalPointCount > 0 {
                let format = "yyyy-MM-dd'T'HH:mm:ss." + String(repeating: "S", count: decimalPointCount) + "Z"
                dateFormatter.dateFormat = format
                if let date = dateFormatter.date(from: self) {
                    return date
                }
                decimalPointCount -= 1
            }
            
            return nil // Unable to parse the date string with any format
        }
    }
    
}
